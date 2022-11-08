package org.skriptlang.addonpatcher;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.skriptlang.addonpatcher.variants.Replacer;
import org.skriptlang.addonpatcher.variants.agent.AgentPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;

public class AddonPatcher extends JavaPlugin {

    private static final int REPLACER = 1;
    private static final int AGENT = 2;
    private int variant = 0;
    private boolean shouldDisable = false;

    public int getVariant() {
        if (variant == 0) {
            // Read variant from jar file (variant.txt)
            // Defaults to REPLACER, otherwise AGENT if specified

            variant = REPLACER;
            Reader reader = getTextResource("variant.txt");
            if (reader != null) {
                BufferedReader bufferedReader = new BufferedReader(reader);
                try {
                    String line = bufferedReader.readLine().toLowerCase(Locale.ROOT);
                    if (line.contains("agent"))
                        variant = AGENT;
                } catch (IOException ignored) {
                }
            }
        }
        return variant;
    }

    public AddonPatcher() {
        super();
        if (getVariant() == REPLACER) {
            Replacer.replaceFiles(getLogger());
        } else if (getVariant() == AGENT) {
            boolean success = AgentPlugin.load(this, getFile());
            if (!success) {
                // Attaching failed, disable plugin
                shouldDisable = true;
            }
        }
    }

    @Override
    public void onEnable() {
        UpdateChecker.check();

        if (shouldDisable) {
            getLogger().severe("AddonPatcher couldn't be started. Scroll all the way up in console to see the errors.");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

}
