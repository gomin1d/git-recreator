package ua.lokha.gitrecreator;

import com.google.gson.Gson;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) throws Exception {
        File from = null;
        File to = null;

        int index = 0;
        boolean continueRecreate = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                if (args[i].equals("--continue")) {
                    continueRecreate = true;
                }
            } else {
                if (index == 0) {
                    from = new File(args[index]);
                } else if (index == 1) {
                    to = new File(args[index]);
                }
                index++;
            }
        }

        if (from == null || to == null) {
            throw new IllegalArgumentException("укажите два аргумента путей, первый на оригинальный репозиторий, второй на копию");
        }

        File file = new File("git-recreator.json");
        if (continueRecreate && !file.exists()) {
            throw new IllegalStateException("не выйдет продолжить пересоздание, файл git-recreator.json не найден");
        }

        Gson gson = new Gson();
        GitRecreator recreator = null;
        try {
            if (file.exists()) {
                Storage storage = gson.fromJson(FileUtils.readFileToString(file, StandardCharsets.UTF_8), Storage.class);
                recreator = Storage.from(storage);
                recreator.continueRecreate();
            } else {
                recreator = new GitRecreator(from, to);
                recreator.recreate();
            }
            file.delete();
        } catch (Exception e) {
            if (recreator != null) {
                System.out.println("сохраняем результат в файл git-recreator.json, продолжить флагом --continue");
                Storage storage = Storage.to(recreator);
                FileUtils.writeStringToFile(file, gson.toJson(storage), StandardCharsets.UTF_8);
            }
            e.printStackTrace();
        }
    }
}
