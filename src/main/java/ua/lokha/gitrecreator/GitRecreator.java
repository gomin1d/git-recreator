package ua.lokha.gitrecreator;

import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

public class GitRecreator {

    private List<Commit> commits = new ArrayList<>();
    private File from;
    private File to;

    public GitRecreator(File from, File to) {
        this.from = from;
        this.to = to;
    }

    @SneakyThrows
    public void recreate() {
        if (!from.exists()) {
            throw new FileNotFoundException(from.getAbsolutePath());
        }

        FileUtils.deleteDirectory(to);
        FileUtils.forceMkdir(to);
        for (File file : from.listFiles((dir, name) -> name.endsWith(".patch"))) {
            file.delete();
        }


        this.loadCommits();

        if (commits.isEmpty()) {
            System.out.println("commits count is 0");
            return;
        }

        executeTo("git init");

        int branchId = 0;
        for (Commit commit : commits) {
            if (commit.getParents().size() > 1) {
                Commit mergeTo = commit.getParents().get(0);
                Commit mergeFrom = commit.getParents().get(1);
                System.out.println("merge " + mergeFrom.getOldHash() + " to " + mergeTo.getOldHash());

                executeTo("git checkout " + mergeTo.getNewHash());
                List<String> result = execute(to, "git merge " + mergeFrom.getNewHash(), true);
                boolean conflict = false;
                for (String line : result) {
                    if (line.startsWith("CONFLICT ")) {
                        if (!line.startsWith("CONFLICT (content): Merge conflict in ")) {
                            throw new IllegalStateException(line);
                        }
                        conflict = true;
                        String path = line.replace("CONFLICT (content): Merge conflict in ", "");
                        File fromFile = new File(from, path.replace("/", File.separator));
                        File toFile = new File(to, path.replace("/", File.separator));
                        System.out.println("copy " + fromFile.getAbsolutePath() + " to " + toFile.getAbsolutePath());
                        executeFrom("git checkout " + commit.getOldHash());
                        FileUtils.copyFile(fromFile, toFile);
                        executeTo("git add " + path);
                    }
                }

                if (conflict) {
                    executeTo("git merge --continue");
                }

                String newHash = executeTo("git rev-parse HEAD").get(0);
                commit.setNewHash(newHash);

                if (commit.getChildren().isEmpty()) {
                    executeTo("git checkout -b branch-" + branchId++);
                }
            } else {
                String patchName = executeFrom("git format-patch -1 " + commit.getOldHash()).get(0);
                File patch = new File(from, patchName);
                if (!commit.getParents().isEmpty()) {
                    executeTo("git checkout " + commit.getParents().get(0).getNewHash());
                }
                executeTo("git am < " + patch.getAbsolutePath());
                String newHash = executeTo("git rev-parse HEAD").get(0);
                commit.setNewHash(newHash);
                patch.delete();

                if (commit.getChildren().isEmpty()) {
                    executeTo("git checkout -b branch-" + branchId++);
                }
            }
        }
    }

    private void loadCommits() {
        List<String> commitsRaw = executeFrom("git log --all --oneline --no-abbrev --no-decorate");
        Collections.reverse(commitsRaw);

        for (String line : commitsRaw) {
            try {
                String[] data = line.split(" ", 2);
                String hash = data[0];
                String message = data[1];
                Commit commit = new Commit(hash);
                commit.setMessage(message);
                commits.add(commit);
            } catch(Exception e) {
                System.out.println("Ошибка в цикле при обработке line: " + line);;
                throw e;
            }
        }

        List<String> childrenRaw = executeFrom("git rev-list --children --all");
        for (String line : childrenRaw) {
            String[] data = line.split(" ");
            String parentHash = data[0];
            Commit parent = this.getCommitByHash(parentHash);
            for (int i = 1; i < data.length; i++) {
                String childrenHash = data[i];
                Commit children = this.getCommitByHash(childrenHash);
                parent.getChildren().add(children);
            }
        }

        List<String> parentsRaw = executeFrom("git rev-list --parents --all");
        for (String line : parentsRaw) {
            String[] data = line.split(" ");
            String childrenHash = data[0];
            Commit children = this.getCommitByHash(childrenHash);
            for (int i = 1; i < data.length; i++) {
                String parentHash = data[i];
                Commit parent = this.getCommitByHash(parentHash);
                children.getParents().add(parent);
            }
        }
    }

    public Commit getCommitByHash(String hash) {
        return commits.stream()
                .filter(commit -> commit.getOldHash().equals(hash))
                .findFirst().orElseThrow(()->new NoSuchElementException(hash));
    }

    public List<String> executeTo(String command) {
        return execute(to, command, false);
    }

    public List<String> executeFrom(String command) {
        return execute(from, command, false);
    }

    @SneakyThrows
    public List<String> execute(File base, String command, boolean ignoreError) {
        System.out.println("execute: " + command);
        ProcessBuilder builder = new ProcessBuilder(
                "cmd.exe", "/c", "cd \"" + base.getAbsolutePath() + "\" && " + command);
        builder.redirectErrorStream(true);
        Process p = builder.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        List<String> result = new ArrayList<>();
        while (true) {
            String line = r.readLine();
            if (line == null) { break; }
            result.add(line);
        }

        int limit = 0;
        for (String line : result) {
            if (limit++ == 20) {
                System.out.println("...");
                break;
            }
            System.out.println(line);
        }

        int exitCode = p.waitFor();
        if (exitCode != 0 && !ignoreError) {
            throw new IllegalStateException("return exit code " + exitCode + " is not 0");
        }
        return result;
    }
}
