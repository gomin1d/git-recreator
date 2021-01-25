package ua.lokha.gitrecreator;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.similarity.JaroWinklerDistance;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Setter
public class GitRecreator {

    private LinkedList<Commit> commitsQueue = new LinkedList<>();
    private Map<String, Commit> commits = new HashMap<>();

    private File from;
    private File to;

    private  File gitMessage = new File("git-message.txt");
    {
        gitMessage.deleteOnExit();
    }

    private Set<String> deleteChild;
    private String rsyncFlags = null;
    private double deleteDuplicatesThreshold = -1;

    private int branchId;

    public GitRecreator(File from, File to) {
        this.from = from;
        this.to = to;
    }

    public GitRecreator(LinkedList<Commit> commitsQueue, Map<String, Commit> commits, int branchId) {
        this.commitsQueue = commitsQueue;
        this.commits = commits;
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

        Commit next = commitsQueue.getFirst();
        System.out.println("начинаем с коммита " + next);

        main: while (next != null) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            System.out.println("iterate " + next);

            if (deleteChild.contains(next.getOldHash())) {
                System.out.println("delele child start " + next.getOldHash());
                next.setMarkDelete(true);
            } else if (!next.getParents().isEmpty() && next.getParents().get(0).isMarkDelete()) {
                System.out.println("delele by parent " + next.getOldHash());
                next.setMarkDelete(true);
            }

            if (!next.isMarkDelete()) {
                for (Commit parent : next.getParents()) {
                    if (parent.getNewHash() == null && !parent.isMarkDelete()) {
                        System.out.println("need parent first " + parent);
                        next = parent;
                        continue main;
                    }
                }

                commit(next);
            }

            commitsQueue.remove(next);
            if (commitsQueue.isEmpty()) {
                next = null;
            } else {
                next = commitsQueue.getFirst();
            }
        }
    }

    @SneakyThrows
    public void commit(Commit commit) {
        System.out.println("start commit " + commit);
        // некоторые коммиты надо пропускать, сюда сохраняется коммит
        // который будет заменен, если текущий коммит надо пропустить
        Commit replace = null;

        if (commit.getParents().size() > 1) { // is merge
            Commit mergeTo = commit.getParents().get(0);
            Commit mergeFrom = commit.getParents().get(1);
            System.out.println("merge " + mergeFrom.getOldHash() + " to " + mergeTo.getOldHash());

            if (mergeTo.isMarkDelete()) {
                executeTo("git checkout " + mergeFrom.getNewHash());
                replace = mergeFrom;
                System.out.println("Merge commit after delete parent");
                commit.setMessage("Merge commit after delete parent: " + commit.getMessage());
            } else if (mergeFrom.isMarkDelete()) {
                executeTo("git checkout " + mergeTo.getNewHash());
                replace = mergeTo;
                System.out.println("Merge commit after delete parent");
                commit.setMessage("Merge commit after delete parent: " + commit.getMessage());
            } else {
                executeTo("git checkout " + mergeTo.getNewHash());
                replace = mergeFrom;
                execute(to, "git merge " + mergeFrom.getNewHash() + " --no-commit", true);
            }
        } else {
            if (!commit.getParents().isEmpty()) {
                executeTo("git checkout " + commit.getParents().get(0).getNewHash());
                replace = commit.getParents().get(0);
            }
        }

        System.out.println("rsync files");
        executeFrom("git checkout " + commit.getOldHash() + " -f");
        executeFrom("git clean -fdx"); // clear untracked files

        executeFrom("rsync -a --delete --progress --exclude .git" +
                (rsyncFlags == null ? "" : (" " + rsyncFlags)) +
                " . \"" + toLinuxPath(to.getAbsolutePath()) + "\"");
        executeTo("git add -A");

        String message = commit.getMessage();
        String messageArg;
        if (message.contains("\"") || message.contains("\n")) {
            FileUtils.writeStringToFile(gitMessage, message, StandardCharsets.UTF_8);
            messageArg = "-F \"" + gitMessage.getAbsolutePath() + "\"";
        } else {
            messageArg = "-m \"" + message + "\"";
        }
        List<String> commitResult = execute(to,"git commit " + messageArg + " " +
                "--date \"" + commit.getDate() + "\" " +
                "--author \"" + commit.getAuthor() + "\"", true);

        if (commitResult.stream().anyMatch(s -> s.contains("nothing to commit"))) {
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
    }

    private static Pattern windowsPathPrefix = Pattern.compile("[A-Z]:");

    public static String toLinuxPath(String path) {
        path = path.replace("\\", "/");
        Matcher matcher = windowsPathPrefix.matcher(path);
        if (matcher.find()) {
            String[] data = path.split(":", 2);
            path = "/" + data[0].toLowerCase() + data[1];
        }
        return path;
    }

    public void loadCommits() {
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

        if (deleteDuplicatesThreshold != -1) {
            while (this.removeDublicates() > 0) {
            }
        }
    }

    private static JaroWinklerDistance jaroWinklerDistance = new JaroWinklerDistance();

    private int removeDublicates() {
        int concat = 0;
        int size = commitsQueue.size();
        for (ListIterator<Commit> iterator = commitsQueue.listIterator(); iterator.hasNext(); ) {
            Commit commit = iterator.next();
            if (deleteChild != null && deleteChild.contains(commit.getOldHash())) {
                continue;
            }
            if (commit.getParents().size() != 1) {
                continue;
            }
            Commit parent = commit.getParents().get(0);
            if (!parent.getAuthor().equals(commit.getAuthor())) {
                continue;
            }
            Double apply = jaroWinklerDistance.apply(
                    StringUtils.remove(parent.getMessage().split("\n")[0], "(With concat)"),
                    StringUtils.remove(commit.getMessage().split("\n")[0], "(With concat)")
            );
            if (apply > deleteDuplicatesThreshold) {
                concat++;
                iterator.remove();
                boolean change = false;
                for (Commit child : commit.getChildren()) {
                    for (int i = 0; i < child.getParents().size(); i++) {
                        if (child.getParents().get(i).equals(commit)) {
                            child.getParents().set(i, parent);
                            change = true;
                        }
                    }
                }
                if (!change && commit.getChildren().size() > 0) {
                    throw new IllegalStateException();
                }
                change = false;
                ListIterator<Commit> listIterator = parent.getChildren().listIterator();
                while (listIterator.hasNext()) {
                    Commit next = listIterator.next();
                    if (next.equals(commit)) {
                        for (int i = 0; i < commit.getChildren().size(); i++) {
                            if (i == 0) {
                                listIterator.set(commit.getChildren().get(i));
                            } else {
                                listIterator.add(commit.getChildren().get(i));
                            }
                        }
                        change = true;
                    }
                }
                if (!change) {
                    throw new IllegalStateException();
                }

                String message = parent.getMessage();
                if (!message.contains("\n")) {
                    message += " (With concat)\n\n";
                } else {
                    message += "\n";
                }
                message += "Concat commit: " + commit.getMessage() + " " + commit.getDate() + " (old hash " + commit.getOldHash() + ")";
                parent.setMessage(message);
                parent.setOldHash(commit.getOldHash());
                commits.put(commit.getOldHash(), parent);
            }
        }

        System.out.println(concat + "/" + size + " (-> " + commits.size() + ")");
        return concat;
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
