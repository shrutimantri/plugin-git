package io.kestra.plugin.git;

import io.kestra.core.models.tasks.NamespaceFiles;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.utils.IdUtils;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.eclipse.jgit.lib.Constants.R_HEADS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@MicronautTest
class PushTest {
    public static final String BRANCH = "ci";
    public static final String INPUT_FILE_NAME = "input_file.txt";
    @Inject
    private RunContextFactory runContextFactory;

    @Value("${kestra.git.pat}")
    private String pat;

    @Inject
    private StorageInterface storageInterface;

    @Test
    void cloneThenPush_OnlyNeedsCredentialsForPush() throws Exception {
        Clone clone = Clone.builder()
            .id("clone")
            .type(Clone.class.getName())
            .url("https://github.com/kestra-io/unit-tests")
            .username(pat)
            .password(pat)
            .branch(BRANCH)
            .build();

        RunContext cloneRunContext = runContextFactory.of();
        clone.run(cloneRunContext);

        String expectedInputFileContent = IdUtils.create();
        Push push = Push.builder()
            .id("push")
            .type(Push.class.getName())
            .commitMessage("Push from CI - Clone then push")
            .inputFiles(Map.of(
                INPUT_FILE_NAME, expectedInputFileContent
            ))
            .username(pat)
            .password(pat)
            .branch(BRANCH)
            .build();
        Push.Output pushOutput = push.run(cloneRunContext);

        cloneRunContext = runContextFactory.of();
        Clone.Output cloneOutput = clone.run(cloneRunContext);

        String fileContent = FileUtils.readFileToString(Path.of(cloneOutput.getDirectory()).resolve(INPUT_FILE_NAME).toFile(), "UTF-8");
        assertThat(fileContent, is(expectedInputFileContent));

        assertThat(pushOutput.getCommitId(), is(getLastCommitId(cloneRunContext)));
    }

    private static String getLastCommitId(RunContext runContext) throws GitAPIException, IOException {
        RevCommit lastCommit = StreamSupport.stream(
            Git.open(runContext.tempDir().toFile())
                .log()
                .call().spliterator(),
            false
        ).findFirst().get();
        return lastCommit.getId().getName();
    }

    @Test
    void cloneThenPush_PushBranchContentToAnother() throws Exception {
        Clone clone = Clone.builder()
            .id("clone")
            .type(Clone.class.getName())
            .url("https://github.com/kestra-io/unit-tests")
            .username(pat)
            .password(pat)
            .branch(BRANCH)
            .build();

        RunContext cloneRunContext = runContextFactory.of();
        clone.run(cloneRunContext);
        String ciBranchExpectedLastCommitId = getLastCommitId(cloneRunContext);

        String otherBranch = IdUtils.create();
        Push push = Push.builder()
            .id("push")
            .type(Push.class.getName())
            .commitMessage("Push from CI - Clone then push")
            .username(pat)
            .password(pat)
            .branch(otherBranch)
            .build();
        Push.Output pushOutput = push.run(cloneRunContext);

        try {
            assertThat(pushOutput.getCommitId(), not(is(ciBranchExpectedLastCommitId)));

            RunContext ciBranchContext = runContextFactory.of();
            clone.run(ciBranchContext);

            assertThat(getLastCommitId(ciBranchContext), is(ciBranchExpectedLastCommitId));
        } finally {
            deleteRemoteBranch(cloneRunContext.tempDir().toString(), otherBranch);
        }
    }

    @Test
    void oneTaskPush_ExistingBranch() throws Exception {
        String namespace = "my-namespace";
        String tenantId = "my-tenant";
        RunContext runContext = runContextFactory.of(Map.of(
            "flow", Map.of(
                "tenantId", tenantId,
                "namespace", namespace
            ),
            "description", "One-task push"
        ));

        String expectedInputFileContent = IdUtils.create();
        String expectedNamespaceFileContent = IdUtils.create();

        String namespaceFileName = "namespace_file.txt";
        try(ByteArrayInputStream is = new ByteArrayInputStream(expectedNamespaceFileContent.getBytes())) {
            storageInterface.put(
                tenantId,
                URI.create(Path.of(storageInterface.namespaceFilePrefix(namespace), namespaceFileName).toString()),
                is
            );
        }

        String shouldNotBeCommitted = "do_not_commit";
        Push push = Push.builder()
            .id("push")
            .type(Push.class.getName())
            .url("https://github.com/kestra-io/unit-tests")
            .commitMessage("Push from CI - {{description}}")
            .username(pat)
            .password(pat)
            .inputFiles(Map.of(
                INPUT_FILE_NAME, expectedInputFileContent,
                shouldNotBeCommitted, "should not be committed"
            ))
            .namespaceFiles(NamespaceFiles.builder()
                .enabled(true)
                .build()
            )
            .addFilesPattern(List.of(
                INPUT_FILE_NAME,
                namespaceFileName
            ))
            .branch(BRANCH)
            .build();

        push.run(runContext);

        Clone clone = Clone.builder()
            .id("clone")
            .type(Clone.class.getName())
            .url("https://github.com/kestra-io/unit-tests")
            .username(pat)
            .password(pat)
            .branch(BRANCH)
            .build();

        Clone.Output cloneOutput = clone.run(runContextFactory.of());

        String fileContent = FileUtils.readFileToString(Path.of(cloneOutput.getDirectory()).resolve(INPUT_FILE_NAME).toFile(), "UTF-8");
        assertThat(fileContent, is(expectedInputFileContent));
        assertThat(new File(cloneOutput.getDirectory(), shouldNotBeCommitted).exists(), is(false));

        fileContent = FileUtils.readFileToString(Path.of(cloneOutput.getDirectory()).resolve(namespaceFileName).toFile(), "UTF-8");
        assertThat(fileContent, is(expectedNamespaceFileContent));
    }

    @Test
    void oneTaskPush_NonExistingBranch() throws Exception {
        String branchName = IdUtils.create();
        Clone clone = Clone.builder()
            .id("clone")
            .type(Clone.class.getName())
            .url("https://github.com/kestra-io/unit-tests")
            .username(pat)
            .password(pat)
            .branch(branchName)
            .build();

        Assertions.assertThrows(TransportException.class, () -> clone.run(runContextFactory.of()));

        Push push = Push.builder()
            .id("push")
            .type(Push.class.getName())
            .url("https://github.com/kestra-io/unit-tests")
            .commitMessage("Branch creation")
            .username(pat)
            .password(pat)
            .branch(branchName)
            .build();
        push.run(runContextFactory.of());

        RunContext runContext = runContextFactory.of();
        try {
            Clone.Output run = clone.run(runContext);
        } finally {
            deleteRemoteBranch(runContext.tempDir().toString(), branchName);
        }
    }

    @Test
    void oneTaskPush_WithSpecifiedDirectory() throws Exception {
        RunContext runContext = runContextFactory.of();

        String expectedInputFileContent = IdUtils.create();
        String expectedNestedInputFileContent = IdUtils.create();

        String directory = "cloning-directory";
        String nestedFilePath = "subdir/nested.txt";
        Push push = Push.builder()
            .id("push")
            .type(Push.class.getName())
            .url("https://github.com/kestra-io/unit-tests")
            .commitMessage("Push from CI - One-task push with specified directory")
            .username(pat)
            .password(pat)
            .directory(directory)
            .inputFiles(Map.of(
                "not_included_file.txt", "not included",
                INPUT_FILE_NAME, "not included neither",
                directory + "/" + INPUT_FILE_NAME, expectedInputFileContent,
                directory + "/" + nestedFilePath, expectedNestedInputFileContent
            ))
            .branch(BRANCH)
            .build();

        push.run(runContext);

        Clone clone = Clone.builder()
            .id("clone")
            .type(Clone.class.getName())
            .url("https://github.com/kestra-io/unit-tests")
            .username(pat)
            .password(pat)
            .branch(BRANCH)
            .build();

        Clone.Output cloneOutput = clone.run(runContextFactory.of());

        String fileContent = FileUtils.readFileToString(Path.of(cloneOutput.getDirectory()).resolve(INPUT_FILE_NAME).toFile(), "UTF-8");
        assertThat(fileContent, is(expectedInputFileContent));

        fileContent = FileUtils.readFileToString(Path.of(cloneOutput.getDirectory()).resolve(nestedFilePath).toFile(), "UTF-8");
        assertThat(fileContent, is(expectedNestedInputFileContent));
    }

    private void deleteRemoteBranch(String gitDirectory, String branchName) throws GitAPIException, IOException {
        try(Git git = Git.open(new File(gitDirectory))) {
            git.checkout().setName("tmp").setCreateBranch(true).call();
            git.branchDelete().setBranchNames(R_HEADS + branchName).call();
            RefSpec refSpec = new RefSpec()
                .setSource(null)
                .setDestination(R_HEADS + branchName);
            git.push().setCredentialsProvider(new UsernamePasswordCredentialsProvider(pat, pat)).setRefSpecs(refSpec).setRemote("origin").call();
        }
    }
}
