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

    private static final String BRANCH_LINE_PREFIX = "branch ";
    private static final String COMMIT_LINE_PREFIX = "commit ";
    private static final int MAX_REPORTED_OUTPUT = 500;

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
     * Get the head commit of a named branch.
     *
     * <p>Reads the server-side branch listing, so it neither requires nor changes workspace
     * state. Callers must not check anything out to make this accurate.
     *
     * @param branchName Branch to look up, matched exactly as configured on the VCS root
     * @throws VcsException if the repository has no branch with that name
     */
    @NotNull
    public String getBranchHead(@NotNull String branchName) throws VcsException {
        return parseBranchHead(execute("branch"), branchName);
    }

    /**
     * Extract one branch's head commit from 'dv branch' output.
     *
     * <p>The listing is a block per branch, ordered by ascending branch id:
     * <pre>
     * branch main (dv.branch.1)
     * commit dv.commit.201
     *
     * branch Development (dv.branch.6)
     * commit dv.commit.303
     * </pre>
     *
     * <p>Matching is exact and case-sensitive. Falling back to a near-miss branch would
     * silently poll the wrong branch, which is the failure this method exists to prevent.
     */
    @NotNull
    static String parseBranchHead(@NotNull String output, @NotNull String branchName) throws VcsException {
        List<String> found = new ArrayList<>();
        String currentBranch = null;

        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith(BRANCH_LINE_PREFIX)) {
                currentBranch = stripTrailingId(line.substring(BRANCH_LINE_PREFIX.length()));
                found.add(currentBranch);
            } else if (line.startsWith(COMMIT_LINE_PREFIX) && currentBranch != null) {
                if (currentBranch.equals(branchName)) {
                    return stripTrailingId(line.substring(COMMIT_LINE_PREFIX.length()));
                }
                currentBranch = null;
            }
        }

        if (found.isEmpty()) {
            // 'dv' exits 0 when it is logged out, printing the reason instead of a listing,
            // so an empty result means "could not read branches", not "repository is empty".
            throw new VcsException("Could not read a branch listing from 'dv branch'. Check that the"
                                   + " VCS root's working directory is a Diversion workspace and that"
                                   + " dv is logged in as the account running TeamCity. Output: "
                                   + abbreviate(output.trim()));
        }
        throw new VcsException("Repository has no branch named '" + branchName
                              + "'. Branches found: " + found);
    }

    @NotNull
    private static String abbreviate(@NotNull String value) {
        return value.length() <= MAX_REPORTED_OUTPUT ? value
               : value.substring(0, MAX_REPORTED_OUTPUT) + "... (truncated)";
    }

    /** Drop a trailing parenthesised id, e.g. "Development (dv.branch.6)" -> "Development". */
    @NotNull
    private static String stripTrailingId(@NotNull String value) {
        String trimmed = value.trim();
        // lastIndexOf so a branch name containing '(' keeps it
        int paren = trimmed.lastIndexOf('(');
        return paren > 0 ? trimmed.substring(0, paren).trim() : trimmed;
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
