
package com.gillodaby.bettertorch;

import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public class BetterTorchPlugin extends JavaPlugin {

    public BetterTorchPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
    }

    @Override
    public void start() {
        BetterTorch.init();
        System.out.println("[BetterTorch] Started: torch light boost enabled.");
    }
}
