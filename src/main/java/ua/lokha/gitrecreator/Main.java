package ua.lokha.gitrecreator;

import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        File from = new File(args[0]);
        File to = new File(args[1]);

        GitRecreator recreator = new GitRecreator(from, to);
        recreator.recreate();
    }
}
