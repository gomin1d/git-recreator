package ua.lokha.gitrecreator;

import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
public class GitRecreator {

    private LinkedList<Commit> commitsQueue = new LinkedList<>();
    private Map<String, Commit> commits = new HashMap<>();

    private File from;
    private File to;

    private int branchId;

    public GitRecreator(File from, File to) {
        this.from = from;
        this.to = to;
    }

    public GitRecreator(LinkedList<Commit> commitsQueue, Map<String, Commit> commits, File from, File to, int branchId) {
        this.commitsQueue = commitsQueue;
        this.commits = commits;
        this.from = from;
        this.to = to;
        this.branchId = branchId;
    }

    @SneakyThrows
    public void recreate() {
        if (!from.exists()) {
            throw new FileNotFoundException(from.getAbsolutePath());
        }

        execute(to, "rm -rf *", true);
        execute(to, "rm -rf .*", true);
        FileUtils.forceMkdir(to);
        for (File file : from.listFiles((dir, name) -> name.endsWith(".patch"))) {
            file.delete();
        }
        executeFrom("git clean -fdx"); // clear untracked files

        this.loadCommits();

        executeTo("git init");

        continueRecreate();
    }

    @SneakyThrows
    public void continueRecreate() {
        if (commitsQueue.isEmpty()) {
            System.out.println("commits count is 0");
            return;
        }

        Pattern windowsPathPrefix = Pattern.compile("[A-Z]:");

        Commit next = commitsQueue.getFirst();
        System.out.println("начинаем с коммита " + next);

        main: while (next != null) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            System.out.println("iterate " + next);

            for (Commit parent : next.getParents()) {
                if (parent.getNewHash() == null) {
                    System.out.println("need parent first " + parent);
                    next = parent;
                    continue main;
                }
            }

            Commit commit = next;

            // некоторые коммиты надо пропускать, сюда сохраняется коммит
            // который будет заменен, если текущий коммит надо пропустить
            Commit replace = null;

            if (commit.getParents().size() > 1) { // is merge
                Commit mergeTo = commit.getParents().get(0);
                Commit mergeFrom = replace = commit.getParents().get(1);
                System.out.println("merge " + mergeFrom.getOldHash() + " to " + mergeTo.getOldHash());

                executeTo("git checkout " + mergeTo.getNewHash());
                execute(to, "git merge " + mergeFrom.getNewHash() + " --no-commit", true);

            } else {
                if (!commit.getParents().isEmpty()) {
                    executeTo("git checkout " + commit.getParents().get(0).getNewHash());
                    replace = commit.getParents().get(0);
                }
            }

            System.out.println("rsync files");
            executeFrom("git checkout " + commit.getOldHash() + " -f");
            executeFrom("git clean -fdx"); // clear untracked files

            String path = to.getAbsolutePath().replace("\\", "/");
            Matcher matcher = windowsPathPrefix.matcher(path);
            if (matcher.find()) {
                String[] data = path.split(":", 2);
                path = "/" + data[0].toLowerCase() + data[1];
            }

            executeFrom("rsync -a --delete --progress --exclude .git . \"" + path + "\"");
            executeTo("git add -A");

            String messageArg = commit.getMessage();
            messageArg = messageArg.replace("\"", "\\\"");
            messageArg = messageArg.replace("\n", "\\n");
            List<String> commitResult = execute(to,"git commit -m \"" + messageArg + "\" " +
                    "--date \"" + commit.getDate() + "\" " +
                    "--author \"" + commit.getAuthor() + "\"", true);

            if (commitResult.stream().anyMatch(s -> s.contains("nothing to commit, working tree clean"))) {
                // fast forward
                System.out.println("nothing to commit, replace " + commit.getOldHash() + " " + (replace == null ? null : replace.getOldHash()));
                for (Commit child : commit.getChildren()) {
                    ListIterator<Commit> iterator = child.getParents().listIterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().equals(commit)) {
                            if (replace == null) {
                                iterator.remove();
                            } else {
                                iterator.set(replace);
                            }
                        }
                    }
                }
                if (replace != null) {
                    replace.setChildren(commit.getChildren());
                }
                commit = replace;
            } else {
                String newHash = executeTo("git rev-parse HEAD").get(0);
                commit.setNewHash(newHash);
            }

            if (commit != null && commit.getChildren().isEmpty()) {
                executeTo("git checkout -b branch-" + branchId++);
            }

            commitsQueue.remove(next);
            if (commitsQueue.isEmpty()) {
                next = null;
            } else {
                next = commitsQueue.getFirst();
            }
        }
    }

    private void loadCommits() {
        List<String> commitsRaw = executeFrom("git log --all --no-abbrev --no-decorate");

        Iterator<String> it = commitsRaw.iterator();
        String start = it.next();
        while (it.hasNext()) {
            String hash = start;
            if (!hash.startsWith("commit ")) {
                throw new IllegalStateException("is no hash: " + hash);
            }
            hash = hash.replace("commit ", "");

            String author;
            do {
                author = it.next();
            }while (!author.startsWith("Author: "));
            author = author.replace("Author: ", "");

            String date;
            do {
                date = it.next();
            }while (!date.startsWith("Date:   "));
            date = date.replace("Date:   ", "");

            while (!it.next().equals("")) {} // skip start message empty line

            StringBuilder builder = new StringBuilder();
            while (it.hasNext()) {
                String next = it.next();
                if (next.startsWith("commit ")) {
                    start = next;
                    break;
                } else {
                    if (builder.length() > 0) {
                        builder.append("\n");
                    }
                    builder.append(next.replace("    ", ""));
                }
            }

            Commit commit = new Commit(hash);
            commit.setAuthor(author);
            commit.setDate(date);
            String message = builder.toString();
            if (message.endsWith("\n")) {
                message = message.substring(0, message.length() - 1);
            }
            commit.setMessage(message);
            commitsQueue.add(commit);
            commits.put(commit.getOldHash(), commit);
        }

        Collections.reverse(commitsQueue);

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
        Commit commit = commits.get(hash);
        if (commit == null) {
            throw new NoSuchElementException(hash);
        }
        return commit;
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
        ProcessBuilder builder;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            builder = new ProcessBuilder(
                    "cmd.exe", "/c", "cd \"" + base.getAbsolutePath() + "\" && " + command);
        } else {
            builder = new ProcessBuilder("bash", "-c", "cd \"" + base.getAbsolutePath() + "\" && " + command);
        }
        try {
            builder.redirectErrorStream(true);
            builder.directory(base);
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
        } catch (Exception e) {
            if (ignoreError) {
                e.printStackTrace();
                return Collections.emptyList();
            } else {
                throw e;
            }
        }
    }
}
