package org.skriptlang.addonpatcher;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class UpdateChecker {

    private static final String UPDATE_URL = "https://api.github.com/repos/SkriptLang/AddonPatcher/releases/latest";

    public static void check() {
        AddonPatcher addonPatcher = (AddonPatcher) Bukkit.getPluginManager().getPlugin("AddonPatcher");
        Objects.requireNonNull(addonPatcher);
        Logger logger = addonPatcher.getLogger();
        CompletableFuture.runAsync(() -> {
            URL url;
            try {
                url = new URL(UPDATE_URL);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            JsonObject jsonObject;
            try {
                URLConnection urlConnection = url.openConnection();
                if (!(urlConnection instanceof HttpURLConnection))
                    throw new IllegalStateException();
                HttpURLConnection httpConnection = (HttpURLConnection) urlConnection;

                int responseCode = httpConnection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    // No update published yet, disable update checker
                    return;
                } else if (responseCode != HttpURLConnection.HTTP_OK) {
                    // Unexpected status code received, error
                    InputStream errorStream = httpConnection.getErrorStream();
                    if (errorStream != null) { // (null check includes check if response code is actual error)
                        BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(errorStream, StandardCharsets.UTF_8));
                        String error = errorReader.lines().collect(Collectors.joining("\n"));

                        throw new IOException("Unexpected response code: " + responseCode + " (" + error + ")");
                    }

                    throw new IOException("Unexpected response code: " + responseCode);
                }

                InputStream inputStream = httpConnection.getInputStream();
                Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);

                jsonObject = new Gson().fromJson(reader, JsonElement.class).getAsJsonObject();
            } catch (IOException e) {
                throw new CompletionException(e);
                // Wrap in CompletionException so it can be thrown from Runnable
            }
            String updateTag = jsonObject.get("tag_name").getAsString();
            String updateUrl = jsonObject.get("html_url").getAsString();

            // Check if tags don't equal, since any update will be released on GH,
            //  this checks if they have the latest update
            if (!(updateTag.equals(addonPatcher.getDescription().getVersion())
                    || updateTag.equals("v" + addonPatcher.getDescription().getVersion())
            )) {
                // Update needed, warn user
                // (single line logger usage, no need to run on main thread)
                logger.warning("New update available: " + updateTag + ". Get it here: " + updateUrl);
            }
        }).exceptionally(t -> {
            // Run prints on main thread, so they're more likely to be on subsequent lines
            Bukkit.getScheduler().runTask(addonPatcher, () -> {
                logger.warning("Couldn't check for updates, please report this error if your internet connection works properly.");

                Throwable throwable = t;
                if (throwable instanceof CompletionException && throwable.getCause() != null)
                    throwable = throwable.getCause();
                throwable.printStackTrace();
            });

            return null;
        });
    }

}
