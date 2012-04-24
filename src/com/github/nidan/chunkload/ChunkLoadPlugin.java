package com.github.nidan.chunkload;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.wepif.PermissionsResolverManager;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.selections.CuboidSelection;
import com.sk89q.worldedit.bukkit.selections.Selection;

public class ChunkLoadPlugin extends JavaPlugin implements Listener
{
	private Map<String, Set<Chunk>> keep_loaded;
	private FileConfiguration conf;
	private WorldEditPlugin we;
	
	boolean ops;
	
	public ChunkLoadPlugin()
	{
		keep_loaded = new HashMap<String, Set<Chunk>>();
	}
	
	public void onEnable()
	{
		keep_loaded.clear();
		getServer().getPluginManager().registerEvents(this, this);
		Object o = getServer().getPluginManager().getPlugin("WorldEdit");
		if(o != null && o instanceof WorldEditPlugin) {we = (WorldEditPlugin) o;}
		else {we = null;}
		PermissionsResolverManager.initialize(this);
		loadData();
	}
	
	private void loadData()
	{
		conf = getConfig();
		if(!conf.contains("worlds"))
		{
			if(conf.getConfigurationSection("worlds").getKeys(false).size() > 0)
			{
				/* silently update config file of previous versions - move anything to worlds */
				conf.createSection("worlds", conf.getValues(true));
				for(String k : conf.getKeys(false)) {if(!k.equals("worlds")) {conf.set(k, null);}}
			}
			else {conf.createSection("worlds");}
		}
		if(!conf.isBoolean("allow-ops")) {conf.set("allow-ops", true);}
		ops = conf.getBoolean("allow-ops");
		Set<String> worlds = conf.getConfigurationSection("worlds").getKeys(false);
		for(String w: worlds) {loadData(w);}
		saveConfig();
	}
	
	private void loadData(String w)
	{
		World world = null;
		for(World cw : getServer().getWorlds()) {if(sanitizeWorld(cw.getName()).equals(w)) {world = cw; break;}}
		if(world != null) {loadData(w, world);}
	}
	
	private void loadData(String w, World world)
	{
		ConfigurationSection worldconf = conf.getConfigurationSection("worlds." + w);
		if(worldconf == null) {return;}
		Set<String> regions = worldconf.getKeys(false);
		Set<Chunk> chunks = new HashSet<Chunk>();
		for(String r: regions)
		{
			ConfigurationSection regionconf = worldconf.getConfigurationSection(r);
			int xmin = regionconf.getInt("xmin");
			int xmax = regionconf.getInt("xmax");
			if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;} 
			int zmin = regionconf.getInt("zmin");
			int zmax = regionconf.getInt("zmax");
			if(zmax < zmin) {int tmp = zmax; zmax = zmin; zmin = tmp;}
			for(int x = xmin; x <= xmax; x++) for(int z = zmin; z <= zmax; z++)
			{
				chunks.add(world.getChunkAt(x, z));
			}
		}
		keep_loaded.put(w, chunks);
	}
	
	public void onDisable()
	{
		keep_loaded.clear();
	}
	
	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args)
	{
		if(!(sender instanceof Player))
		{
			sender.sendMessage("Must be used by a Player");
			return false;
		}
		Player player;
		player = (Player) sender;
		World world = player.getWorld();
		String w = sanitizeWorld(world.getName());
		
		if(!PermissionsResolverManager.getInstance().hasPermission(player, "chunkload.usage") || (ops && player.isOp()))
		{
			sender.sendMessage(ChatColor.RED + "You don't have permission to use this.");
			return true;
		}
		
		if(args.length == 0) {return false;}
		
		/*
		 * a/add name
		 * r/rm/remove name
		 * list [#]
		 * select name
		 */
		else if((args[0].equals("add") || args[0].equals("a")) && args.length >= 2)
		{
			if(!acceptableName(args[1]))
			{
				sender.sendMessage(ChatColor.RED + "Invalid region name; only A-Za-z0-9_ are allowed!");
			}
			else if(conf.contains("worlds." + w + "." + args[1]))
			{
				sender.sendMessage(ChatColor.RED + "Region '" + args[1] + "' already exists in this world!");
				return true;
			}
			
			if(we == null)
			{
				Object o = getServer().getPluginManager().getPlugin("WorldEdit");
				if(o != null && o instanceof WorldEditPlugin) {we = (WorldEditPlugin) o;}
				else {we = null;}
				
				sender.sendMessage(ChatColor.RED + "Somehow WorldEdit is missing...");
				return true;
			}
			Selection sel = we.getSelection(player);
			if(sel == null || sel.getWorld() != world)
			{
				sender.sendMessage(ChatColor.RED + "Your selection is not in this world");
				return true;
			}
			int xmin = sel.getMinimumPoint().getChunk().getX();
			int xmax = sel.getMaximumPoint().getChunk().getX();
			if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;}
			int zmin = sel.getMinimumPoint().getChunk().getZ();
			int zmax = sel.getMaximumPoint().getChunk().getZ();
			if(zmax < zmin) {int tmp = zmax; zmax = zmin; zmin = tmp;}
			
			ConfigurationSection r = conf.createSection("worlds." + w + "." + args[1]); 
			r.set("xmin", xmin);
			r.set("xmax", xmax);
			r.set("zmin", zmin);
			r.set("zmax", zmax);
			
			saveConfig();
			
			Set<Chunk> chunks = keep_loaded.get(w);
			if(chunks == null)
			{
				chunks = new HashSet<Chunk>();
				keep_loaded.put(w, chunks);
			}
			for(int x = xmin ; x <= xmax; x++) for(int z = zmin; z <= zmax; z++)
			{
				chunks.add(world.getChunkAt(x, z));
			}
			sender.sendMessage(ChatColor.YELLOW + "Keeping " + (xmax - xmin + 1) * (zmax - zmin + 1) + " chunks loaded in region '" + args[1] + "'."); 
			return true;
		}
		else if((args[0].equals("remove") || args[0].equals("rm") || args[0].equals("r")) && args.length >= 2)
		{
			if(!acceptableName(args[1]))
			{
				sender.sendMessage(ChatColor.RED + "Invalid region name; only A-Za-z0-9_ are allowed!");
			}
			else if(conf.contains("worlds." + w + "." + args[1]))
			{
				conf.set("worlds." + w + "." + args[1], null);
				saveConfig();
				loadData(w);
				sender.sendMessage(ChatColor.YELLOW + "Region '" + args[1] + "' removed");
			}
			else {sender.sendMessage(ChatColor.RED + "Region '" + args[1] + "' doesn't exist in this world!");}
			return true;
		}
		else if((args[0].equals("select") || args[0].equals("s")) && args.length >= 2)
		{
			if(!acceptableName(args[1]))
			{
				sender.sendMessage(ChatColor.RED + "Invalid region name; only A-Za-z0-9_ are allowed!");
			}
			else if(conf.contains(w + "." + args[1]))
			{
				int xmin = conf.getInt(w + "." + args[1] + ".xmin");
				int xmax = conf.getInt(w + "." + args[1] + ".xmax");
				if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;} 
				int zmin = conf.getInt(w + "." + args[1] + ".zmin");
				int zmax = conf.getInt(w + "." + args[1] + ".zmax");
				if(zmax < zmin) {int tmp = zmax; zmax = zmin; zmin = tmp;} 
				
				xmin *= 16;
				xmax = xmax * 16 + 15;
				zmin *= 16;
				zmax = zmax * 16 + 15;
				
				Vector pt1 = new Vector(xmin, 0, zmin);
				Vector pt2 = new Vector(xmax, world.getMaxHeight() - 1, zmax);
	            CuboidSelection selection = new CuboidSelection(world, pt1, pt2);
	            we.setSelection(player, selection);
				
				sender.sendMessage(ChatColor.YELLOW + "Region '" + args[1] + "' selected as a cuboid.");
			}
			else {sender.sendMessage(ChatColor.RED + "Region '" + args[1] + "' doesn't exist in this world!");}
			return true;
		}
		else if(args[0].equals("list") || args[0].equals("l"))
		{
			if(conf.getConfigurationSection(w) == null)
			{
				sender.sendMessage(ChatColor.YELLOW + "No regions in this world.");
				return true;
			}
			Set<String> regions = conf.getConfigurationSection("worlds." + w).getKeys(false);
			int start = 1;
			if(args.length >= 2)
			{
				try
				{
					start = Integer.parseInt(args[1]);
					start = start * 10 - 9;
				}
				catch (NumberFormatException e) {start = 1;}
			}
			int done = -start + 1;
			
			SortedSet<String> sortedregions = new TreeSet<String>(regions);
			sender.sendMessage(ChatColor.YELLOW + "Regions " + start + " to " + (start + 9) + " (" + sortedregions.size() + " total):");
			for(String r: sortedregions)
			{
				if(done < 0)
				{
					done++;
					continue;
				}
				int xmin = conf.getInt(w + "." + r + ".xmin");
				int xmax = conf.getInt(w + "." + r + ".xmax");
				if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;} 
				int zmin = conf.getInt(w + "." + r + ".zmin");
				int zmax = conf.getInt(w + "." + r + ".zmax");
				if(zmax < zmin) {int tmp = zmax; zmax = zmin; zmin = tmp;}
				done++;
				sender.sendMessage((start + done - 1) + ": " + ChatColor.YELLOW + r + ": (" + (xmin * 16) + "," + (zmin * 16) + ") (" + (xmax * 16 + 15) + "," + (zmax * 16 + 15) + ") - " + ((xmax - xmin + 1) * (zmax - zmin + 1)) + " Chunks");
				if(done >= 10) {break;}
			}
			if(done == 0) {sender.sendMessage(ChatColor.YELLOW + "There are only " + (start - done) + " regionsd defined.");}
			return true;
		}
		else if(args[0].equals("reload"))
		{
			keep_loaded.clear();
			loadData();
			sender.sendMessage(ChatColor.YELLOW + "Configuratio reloaded.");
		}
		
		return false;	
	}
	
	@EventHandler
	public void onChunkUnload(ChunkUnloadEvent e)
	{
		// Get relevant data from the event
		String w = sanitizeWorld(e.getWorld().getName());
		Chunk c = e.getChunk();
		
		// check if chunk can unload via rectangles or a chunk list
		// cancel the unload event if it's not supposed to unload
		if(keep_loaded.get(w) != null && keep_loaded.get(w).contains(c))
		{
			e.setCancelled(true);
			return;
		}
	}
	
	@EventHandler
	public void onWorldInit(WorldInitEvent e)
	{
		World world = e.getWorld();
		loadData(sanitizeWorld(world.getName()), world);
	}
	
	private String sanitizeWorld(String w)
	{
		return w.replaceAll("\\.", "");
	}
	
	private boolean acceptableName(String s)
	{
		return s.matches("^[A-Za-z0-9_]*$");
	}
}
