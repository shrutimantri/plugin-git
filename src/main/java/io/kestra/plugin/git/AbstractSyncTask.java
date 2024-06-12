package io.kestra.plugin.git;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.KestraIgnore;
import io.kestra.plugin.git.services.GitService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;

import java.io.*;
import java.lang.reflect.ParameterizedType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.kestra.core.utils.Rethrow.*;
import static java.lang.Integer.MAX_VALUE;

/**
 *
 * @param <S> Service class
 * @param <T> Resource type
 * @param <O> Output class
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@Getter
public abstract class AbstractSyncTask<S, T, O extends AbstractSyncTask.Output> extends AbstractCloningTask implements RunnableTask<O> {

    @Schema(
        title = "If `true`, the task will only output modifications without performing any modification to Kestra. If `false` (default), all listed modifications will be applied."
    )
    @PluginProperty
    @Builder.Default
    private boolean dryRun = false;

    public abstract boolean isDelete();

    public abstract String getGitDirectory();

    public abstract String fetchedNamespace();

    private Path createGitDirectory(RunContext runContext) throws IllegalVariableEvaluationException {
        Path syncDirectory = runContext.resolve(Path.of(runContext.render(this.getGitDirectory())));
        syncDirectory.toFile().mkdirs();
        return syncDirectory;
    }

    protected Map<URI, Supplier<InputStream>> gitResourcesContentByUri(Path baseDirectory) throws IOException {
        try (Stream<Path> paths = Files.walk(baseDirectory, this.traverseDirectories() ? MAX_VALUE : 1)) {
            Stream<Path> filtered = paths.skip(1);
            KestraIgnore kestraIgnore = new KestraIgnore(baseDirectory);
            filtered = filtered.filter(path -> !kestraIgnore.isIgnoredFile(path.toString(), true));
            filtered = filtered.filter(path -> !path.toString().contains(".git"));

            return filtered.collect(Collectors.toMap(
                gitPath -> URI.create("/" + baseDirectory.relativize(gitPath) + (gitPath.toFile().isDirectory() ? "/" : "")),
                throwFunction(path -> throwSupplier(() -> {
                    if (Files.isDirectory(path)) {
                        return null;
                    }
                    return Files.newInputStream(path);
                }))
            ));
        }
    }

    protected boolean traverseDirectories() {
        return true;
    }
    
    protected boolean mustKeep(RunContext runContext, T instanceResource) {
        return false;
    }

    protected abstract void deleteResource(S service, String tenantId, String renderedNamespace, T instanceResource) throws IOException;

    protected abstract T simulateResourceWrite(S service, String tenantId, String renderedNamespace, URI uri, InputStream inputStream) throws IOException;

    protected abstract T writeResource(Logger logger, S service, String tenantId, String renderedNamespace, URI uri, InputStream inputStream) throws IOException;

    protected abstract SyncResult wrapper(S service, String renderedGitDirectory, String renderedNamespace, URI resourceUri, T resourceBeforeUpdate, T resourceAfterUpdate) throws IOException;

    private URI createDiffFile(RunContext runContext, S service, String renderedNamespace, Map<URI, URI> gitUriByResourceUri, Map<URI, T> beforeUpdateResourcesByUri, Map<URI, T> afterUpdateResourcesByUri, List<T> deletedResources) throws IOException, IllegalVariableEvaluationException {
        File diffFile = runContext.tempFile(".ion").toFile();

        try (BufferedWriter diffWriter = new BufferedWriter(new FileWriter(diffFile))) {
            List<SyncResult> syncResults = new ArrayList<>();

            String renderedGitDirectory = runContext.render(this.getGitDirectory());
            if (deletedResources != null) {
                deletedResources.stream()
                    .map(throwFunction(deletedResource -> wrapper(
                        service,
                        renderedGitDirectory,
                        renderedNamespace,
                        gitUriByResourceUri.get(this.toUri(service, renderedNamespace, deletedResource)),
                        deletedResource,
                        null
                    ))).forEach(syncResults::add);
            }

            afterUpdateResourcesByUri.entrySet().stream().flatMap(throwFunction(e -> {
                SyncResult syncResult = wrapper(
                    service,
                    renderedGitDirectory,
                    renderedNamespace,
                    gitUriByResourceUri.get(e.getKey()), beforeUpdateResourcesByUri.get(e.getKey()),
                    afterUpdateResourcesByUri.get(e.getKey())
                );
                return syncResult == null ? Stream.empty() : Stream.of(syncResult);
            })).forEach(syncResults::add);

            syncResults.stream().sorted((s1, s2) -> {
                    if (s1.getGitPath() == null) {
                        return s2.getGitPath() == null ? 0 : -1;
                    }
                    if (s2.getGitPath() == null) {
                        return 1;
                    }

                    return s1.getGitPath().compareTo(s2.getGitPath());
                })
                .map(throwFunction(JacksonMapper.ofIon()::writeValueAsString))
                .forEach(throwConsumer(syncResultStr -> {
                    diffWriter.write(syncResultStr);
                    diffWriter.write("\n");
                    runContext.logger().debug(syncResultStr);
                }));
        }

        return runContext.storage().putFile(diffFile);
    }

    public O run(RunContext runContext) throws Exception {
        this.detectPasswordLeaks();
        GitService gitService = new GitService(this);

        gitService.namespaceAccessGuard(runContext, this.fetchedNamespace());

        Git git = gitService.cloneBranch(runContext, runContext.render(this.getBranch()), this.cloneSubmodules);

        Path localGitDirectory = this.createGitDirectory(runContext);
        Map<URI, Supplier<InputStream>> gitContentByUri = this.gitResourcesContentByUri(localGitDirectory);

        String renderedNamespace = runContext.render(this.fetchedNamespace());

        @SuppressWarnings("unchecked")
        S service = ((DefaultRunContext)runContext).getApplicationContext().getBean((Class<S>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0]);

        Map<URI, T> beforeUpdateResourcesByUri = this.fetchResources(service, runContext.tenantId(), renderedNamespace).stream().collect(Collectors.toMap(
            resource -> this.toUri(service, renderedNamespace, resource),
            Function.identity()
        ));
        Map<URI, URI> gitUriByResourceUri = new HashMap<>();
        Map<URI, T> updatedResourcesByUri = gitContentByUri.entrySet().stream()
            .sorted(Comparator.comparing(e -> StringUtils.countMatches(e.getKey().getPath(), "/")))
            .map(throwFunction(e -> {
                InputStream inputStream = e.getValue().get();
                T resource;
                if (this.dryRun) {
                    resource = this.simulateResourceWrite(service, runContext.tenantId(), renderedNamespace, e.getKey(), inputStream);
                } else {
                    resource = this.writeResource(runContext.logger(), service, runContext.tenantId(), renderedNamespace, e.getKey(), inputStream);
                }

                return Pair.of(e.getKey(), resource);
            }))
            .collect(
                HashMap::new,
                (map, pair) -> {
                    URI uri = pair.getLeft();
                    T resource = pair.getRight();
                    URI resourceUri = this.toUri(service, renderedNamespace, resource);
                    map.put(resourceUri, resource);
                    gitUriByResourceUri.put(resourceUri, uri);
                },
                HashMap::putAll
            );

        List<T> deleted;
        if (this.isDelete()) {
            deleted = new ArrayList<>();
            beforeUpdateResourcesByUri.entrySet().stream().filter(e -> !updatedResourcesByUri.containsKey(e.getKey())).forEach(throwConsumer(e -> {
                if (this.mustKeep(runContext, e.getValue())) {
                    return;
                }

                if (!this.dryRun) {
                    this.deleteResource(service, runContext.tenantId(), renderedNamespace, e.getValue());
                }

                deleted.add(e.getValue());
            }));
        } else {
            deleted = null;
        }

        URI diffFileStorageUri = this.createDiffFile(runContext, service, renderedNamespace, gitUriByResourceUri, beforeUpdateResourcesByUri, updatedResourcesByUri, deleted);

        git.close();

        return output(diffFileStorageUri);
    }

    protected abstract List<T> fetchResources(S service, String tenantId, String renderedNamespace) throws IOException;

    protected abstract URI toUri(S service, String renderedNamespace, T resource);

    protected abstract O output(URI diffFileStorageUri);

    @SuperBuilder
    @Getter
    public abstract static class Output implements io.kestra.core.models.tasks.Output {
        public abstract URI diffFileUri();
    }

    @SuperBuilder
    @Getter
    public abstract static class SyncResult {
        private String gitPath;
        private SyncState syncState;
    }

    public enum SyncState {
        ADDED,
        DELETED,
        OVERWRITTEN,
        UPDATED,
        UNCHANGED
    }
}