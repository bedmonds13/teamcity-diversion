package com.diversion.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.vcs.VcsException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes Diversion CLI commands
 */
public class DiversionCommandExecutor {

    private static final Logger LOG = Logger.getInstance(DiversionCommandExecutor.class.getName());

    private final String dvExecutablePath;
    private final File workingDirectory;

    public DiversionCommandExecutor(@NotNull String dvExecutablePath, @Nullable File workingDirectory) {
        this.dvExecutablePath = dvExecutablePath;
        this.workingDirectory = workingDirectory;
    }

    /**
     * Execute a Diversion command
     * Assumes 'dv login' has been run manually before using this executor.
     *
     * @param args Command arguments (e.g., ["log", "-n", "10"])
     * @return Command output
     * @throws VcsException if command fails
     */
    @NotNull
    public String execute(@NotNull List<String> args) throws VcsException {
        List<String> command = new ArrayList<>();
        command.add(dvExecutablePath);
        command.addAll(args);

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDirectory != null) {
                pb.directory(workingDirectory);
            }
            pb.redirectErrorStream(false);

            process = pb.start();

            // Read stdout
            StringBuilder stdout = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (stdout.length() > 0) {
                        stdout.append("\n");
                    }
                    stdout.append(line);
                }
            }

            // Read stderr
            StringBuilder stderr = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (stderr.length() > 0) {
                        stderr.append("\n");
                    }
                    stderr.append(line);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String errorMessage = "Diversion command failed with exit code " + exitCode;
                if (stderr.length() > 0) {
                    errorMessage += "\nStderr: " + stderr.toString();
                }
                if (stdout.length() > 0) {
                    errorMessage += "\nStdout: " + stdout.toString();
                }
                throw new VcsException(errorMessage);
            }

            return stdout.toString();
        } catch (Exception e) {
            if (e instanceof VcsException) {
                throw (VcsException) e;
            }
            throw new VcsException("Failed to execute Diversion command: " + command, e);
        } finally {
            // Ensure process is destroyed if still running
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    /**
     * Execute a Diversion command (varargs version)
     */
    @NotNull
    public String execute(@NotNull String... args) throws VcsException {
        List<String> argList = new ArrayList<>();
        for (String arg : args) {
            argList.add(arg);
        }
        return execute(argList);
    }

    /**
     * Get the commit ID at the workspace's current HEAD.
     *
     * <p>Reads the workspace, not the server, so callers must check out the branch they
     * care about first. ('dv branch' does report every branch's head server-side without
     * touching the workspace, but keys them by branch name only.)
     */
    @NotNull
    public String getCurrentCommitId() throws VcsException {
        String output = getLog(1);
        for (String line : output.trim().split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("commit ")) {
                // Extract commit ID, strip any trailing branch annotation e.g. "(dv.branch.6)"
                String part = trimmed.substring(7).trim();
                int parenIdx = part.indexOf('(');
                if (parenIdx > 0) {
                    part = part.substring(0, parenIdx).trim();
                }
                return part;
            }
        }
        throw new VcsException("Could not parse commit ID from 'dv log -n 1' output: " + output);
    }

    /**
     * Get commit log
     *
     * @param maxResults Maximum number of commits to retrieve
     * @return Log output
     */
    @NotNull
    public String getLog(int maxResults) throws VcsException {
        return execute("log", "-n", String.valueOf(maxResults));
    }

    /**
     * Test connection to Diversion (verify dv command works)
     */
    public void testConnection() throws VcsException {
        execute("repo");
    }

    /**
     * Clone a repository to a local path
     *
     * @param repoId Repository ID (e.g., "dv.repo.12345")
     * @param targetPath Local path where the repository should be cloned
     * @throws VcsException if clone fails
     */
    public void cloneRepository(@NotNull String repoId, @NotNull File targetPath) throws VcsException {
        execute("clone", repoId, targetPath.getAbsolutePath(), "--new-workspace");
    }

    /**
     * Checkout a specific commit, branch, or tag
     *
     * @param ref Reference to checkout (commit ID, branch name, etc.)
     * @param discardChanges If true, discards any local changes
     * @throws VcsException if checkout fails
     */
    public void checkout(@NotNull String ref, boolean discardChanges) throws VcsException {
        // Retry checkout if sync is incomplete (after dv update)
        for (int attempt = 1; attempt <= DiversionSettings.MAX_CHECKOUT_RETRIES; attempt++) {
            try {
                if (discardChanges) {
                    execute("checkout", ref, "--discard-changes");
                } else {
                    execute("checkout", ref);
                }
                return; // Success
            } catch (VcsException e) {
                if (e.getMessage().contains("Sync is incomplete") && attempt < DiversionSettings.MAX_CHECKOUT_RETRIES) {
                    try {
                        Thread.sleep(DiversionSettings.CHECKOUT_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new VcsException("Interrupted while waiting for sync to complete", ie);
                    }
                } else {
                    throw e; // Rethrow if not a sync issue or out of retries
                }
            }
        }

        throw new VcsException("Checkout failed: sync did not complete after " + DiversionSettings.MAX_CHECKOUT_RETRIES + " attempts");
    }

    /**
     * Update workspace to latest changes from the current branch
     *
     * @throws VcsException if update fails
     */
    public void update() throws VcsException {
        execute("update");
    }

    /**
     * Get the list of files for a commit with their status
     * Uses 'dv ls <commitId> <filename>' which outputs JSON to a file
     *
     * @param commitId Commit ID to get files for
     * @return JSON string containing file information with status codes
     * @throws VcsException if the command fails
     */
    @NotNull
    public String getCommitFiles(@NotNull String commitId) throws VcsException {
        File tempFile = null;
        try {
            // Create a temporary file for the output
            tempFile = File.createTempFile("dv-ls-", ".json");

            // Run dv ls <commitId> <outputFile>
            execute("ls", commitId, tempFile.getAbsolutePath());

            // Read the JSON output
            String json = new String(Files.readAllBytes(tempFile.toPath()), "UTF-8");

            return json;
        } catch (Exception e) {
            throw new VcsException("Failed to get file list for commit " + commitId + ": " + e.getMessage(), e);
        } finally {
            // Clean up temp file
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * Check if a Diversion workspace is initialized in the working directory
     *
     * @return true if workspace exists, false otherwise
     */
    public boolean isWorkspaceInitialized() {
        if (workingDirectory == null || !workingDirectory.exists()) {
            return false;
        }

        // Check if .dv directory exists (workspace marker)
        File dvDir = new File(workingDirectory, ".dv");
        return dvDir.exists() && dvDir.isDirectory();
    }

    /**
     * Ensure workspace is initialized, clone if necessary
     *
     * @param repoId Repository ID to clone if workspace doesn't exist
     * @throws VcsException if initialization fails
     */
    public void ensureWorkspaceInitialized(@NotNull String repoId) throws VcsException {
        if (isWorkspaceInitialized()) {
            return; // Already initialized
        }

        if (workingDirectory == null) {
            throw new VcsException("Cannot initialize workspace: working directory is not configured");
        }

        // Create working directory if it doesn't exist
        if (!workingDirectory.exists()) {
            if (!workingDirectory.mkdirs()) {
                throw new VcsException("Failed to create working directory: " + workingDirectory.getAbsolutePath());
            }
        }

        // Clone the repository
        cloneRepository(repoId, workingDirectory);
    }

    /**
     * Create a tag at a specific commit
     *
     * @param tagName Name of the tag to create
     * @param commitId Commit ID to tag
     * @param message Optional tag message
     * @throws VcsException if tag creation fails
     */
    public void createTag(@NotNull String tagName, @NotNull String commitId, @Nullable String message) throws VcsException {
        if (message != null && !message.isEmpty()) {
            execute("tag", tagName, commitId, "-m", message);
        } else {
            execute("tag", tagName, commitId);
        }
    }
}
