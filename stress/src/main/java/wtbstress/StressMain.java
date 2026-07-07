package wtbstress;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TEST-SERVER-ONLY stress harness for WTB's money engine.
 * /wtbstress &lt;players&gt; &lt;seconds&gt; [adversarial|realistic]
 *
 * <p>WIPES the WTB listings + claim_box tables at run start so the audit's
 * whole-table sums reflect only this run.  Never install on a live server.
 */
public final class StressMain extends JavaPlugin implements CommandExecutor {

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        getCommand("wtbstress").setExecutor(this);
        getLogger().warning("WTBStress loaded — TEST SERVERS ONLY (wipes WTB tables per run).");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /wtbstress <players> <seconds> [adversarial|realistic]");
            return true;
        }
        int players, seconds;
        try {
            players = Math.max(1, Math.min(200, Integer.parseInt(args[0])));
            seconds = Math.max(5, Math.min(600, Integer.parseInt(args[1])));
        } catch (NumberFormatException e) {
            sender.sendMessage("players/seconds must be numbers");
            return true;
        }
        boolean realistic = args.length >= 3 && args[2].equalsIgnoreCase("realistic");

        if (!running.compareAndSet(false, true)) {
            sender.sendMessage("A stress run is already in progress.");
            return true;
        }

        // The harness does blocking DB work — never on the main thread.
        Thread t = new Thread(() -> {
            try {
                new Harness(this, players, seconds, realistic).run();
            } catch (Throwable e) {
                getLogger().severe("[WTBSTRESS] HARNESS CRASHED: " + e);
                e.printStackTrace();
            } finally {
                running.set(false);
            }
        }, "WTBStress-Main");
        t.setDaemon(true);
        t.start();

        sender.sendMessage("[WTBSTRESS] started: " + players + " players, " + seconds
                + "s, mode=" + (realistic ? "realistic" : "adversarial"));
        return true;
    }
}
