package com.github.nidan.chunkload;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
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
	private Map<String, Map<Long, ChunkLoadChunk>> keep_loaded;
	private FileConfiguration conf;
	private WorldEditPlugin we;
	
	/* MUST be sorted */
	public static final String[] specialregions = {"__global__"};
	/* MUST be sorted */
	public static final String[] regionoptions = {"inactive", "load-on-start"};
		
	public ChunkLoadPlugin()
	{		
		keep_loaded = new HashMap<String, Map<Long, ChunkLoadChunk>>();
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
			if(conf.getKeys(false).size() > 0)
			{
				/* silently update config file of previous versions - move anything to worlds */
				conf.createSection("worlds", conf.getValues(true));
				for(String k : conf.getKeys(false)) {if(!k.equals("worlds")) {conf.set(k, null);}}
			}
			else {conf.createSection("worlds");}
		}
		if(!conf.contains("allow-ops")) {conf.set("allow-ops", true);}
		Set<String> worlds = conf.getConfigurationSection("worlds").getKeys(false);
		for(String w: worlds) {loadData(w);}
		saveConfig();
	}
	
	private void loadData(String w)
	{
		World world = null;
		for(World cw : getServer().getWorlds()) {if(sanitizeName(cw.getName()).equals(w)) {world = cw; break;}}
		if(world != null) {loadData(w, world);}
	}
	
	private void loadData(String w, World world)
	{
		ConfigurationSection worldconf = conf.getConfigurationSection("worlds." + w);
		if(worldconf == null) {return;}
		Set<String> regions = worldconf.getKeys(false);
		Map<Long, ChunkLoadChunk> chunks = new HashMap<Long, ChunkLoadChunk>();
		for(String r: regions)
		{
			ConfigurationSection rc = worldconf.getConfigurationSection(r);
			if(r.equals("__global__"))
			{
				loadGlobal(w, world, chunks, rc);
				continue;
			}
			int xmin = rc.getInt("xmin");
			int xmax = rc.getInt("xmax");
			if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;} 
			int zmin = rc.getInt("zmin");
			int zmax = rc.getInt("zmax");
			if(zmax < zmin) {int tmp = zmax; zmax = zmin; zmin = tmp;}
			boolean load = rc.getBoolean("config.load-on-start");
			for(int x = xmin; x <= xmax; x++) for(int z = zmin; z <= zmax; z++)
			{
				Chunk c = world.getChunkAt(x, z);
				ChunkLoadChunk clc = chunks.get(c);
				if(clc == null) {clc = new ChunkLoadChunk(world, c);}
				clc.addRegion(rc);
				chunks.put(chunk2long(c), clc);
				if(load) {c.load(false);}
			}
		}
		keep_loaded.put(w, chunks);
	}
	
	private void loadGlobal(String w, World world, Map<Long, ChunkLoadChunk> chunks, ConfigurationSection rc)
	{
		for(File f: world.getWorldFolder().listFiles())
		{
			File regdir;
			if(f.getName().startsWith("DIM")) {regdir = new File(f, "region");}
			else if(f.getName().equals("region")) {regdir = f;}
			else {continue;}
			boolean load = rc.getBoolean("config.load-on-start");
			
			for(File region: regdir.listFiles())
			{
				String rn = region.getName();
				if(!rn.matches("^r\\.-?[0-9]+\\.-?[0-9]+\\.mca$")) {continue;}
				/* open file, read index, transform to chunks */
				int x = Integer.parseInt(rn.substring(2));
				int z = Integer.parseInt(rn.substring(rn.indexOf('.', 2) + 1));
				try
				{
					DataInputStream d = new DataInputStream(new FileInputStream(region));
					for(int i = 0; i < 1024; i++)
					{
						if(d.readInt() == 0) {continue;}
						int cx = x * 32 + (i & 31);
						int cz = z * 32 + ((i >> 5) & 31);
						Chunk c = world.getChunkAt(cx, cz);
						ChunkLoadChunk clc = chunks.get(c);
						if(clc == null) {clc = new ChunkLoadChunk(world, c);}
						clc.addRegion(rc);
						chunks.put(chunk2long(c), clc);
						if(load) {c.load(false);}
					}
					d.close();
				}
				/* error "handling" */
				catch(FileNotFoundException e) {continue;}
				catch(IOException e) {continue;}
			}
			break;
		}
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
			return true;
		}
		Player player;
		player = (Player) sender;
		World world = player.getWorld();
		String w = sanitizeName(world.getName());
		
		if(!PermissionsResolverManager.getInstance().hasPermission(player, "chunkload.usage") && !(conf.getBoolean("allow-ops") && player.isOp()))
		{
			sender.sendMessage(ChatColor.RED + "You don't have permission to use this!");
			return true;
		}
		
		if(args.length == 0) {return false;}
		
		/*
		 * a/add name
		 * r/rm/remove name
		 * l/list [#]
		 * s/select name
		 * c/config name [option [value]]
		 * reload
		 */
		else if((args[0].equals("add") || args[0].equals("a")) && args.length >= 2)
		{
			if(!acceptableName(args[1]))
			{
				sender.sendMessage(ChatColor.RED + "Invalid region name!");
				return true;
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
				sender.sendMessage(ChatColor.RED + "Your selection is not in this world.");
				return true;
			}
			int xmin = sel.getMinimumPoint().getChunk().getX();
			int xmax = sel.getMaximumPoint().getChunk().getX();
			if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;}
			int zmin = sel.getMinimumPoint().getChunk().getZ();
			int zmax = sel.getMaximumPoint().getChunk().getZ();
			if(zmax < zmin) {int tmp = zmax; zmax = zmin; zmin = tmp;}
			
			ConfigurationSection wc = conf.getConfigurationSection("worlds." + w);
			if(wc == null) {wc = conf.createSection("worlds." + w);}
			ConfigurationSection r = wc.createSection(args[1]); 
			r.set("xmin", xmin);
			r.set("xmax", xmax);
			r.set("zmin", zmin);
			r.set("zmax", zmax);
			
			saveConfig();
			
			Map<Long, ChunkLoadChunk> chunks = keep_loaded.get(w);
			if(chunks == null)
			{
				chunks = new HashMap<Long, ChunkLoadChunk>();
				keep_loaded.put(w, chunks);
				
			}
			for(int x = xmin ; x <= xmax; x++) for(int z = zmin; z <= zmax; z++)
			{
				Chunk c = world.getChunkAt(x, z);
				ChunkLoadChunk clc = chunks.get(c);
				if(clc == null) {clc = new ChunkLoadChunk(world, world.getChunkAt(x, z));}
				clc.addRegion(r);
				chunks.put(chunk2long(c), clc);
			}
			sender.sendMessage(ChatColor.YELLOW + "Keeping " + (xmax - xmin + 1) * (zmax - zmin + 1) + " chunks loaded in region '" + args[1] + "'."); 
			return true;
		}
		else if((args[0].equals("remove") || args[0].equals("rm") || args[0].equals("r")) && args.length >= 2)
		{
			if(!acceptableName(args[1]))
			{
				sender.sendMessage(ChatColor.RED + "Invalid region name!");
			}
			else if(conf.contains("worlds." + w + "." + args[1]))
			{
				ConfigurationSection r = conf.getConfigurationSection("worlds." + w + "." + args[1]);
				int xmin = r.getInt("xmin");
				int xmax = r.getInt("xmax");
				if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;} 
				int zmin = r.getInt("zmin");
				int zmax = r.getInt("zmax");
				if(zmax < zmin) {int tmp = zmax; zmax = zmin; zmin = tmp;}
				conf.set("worlds." + w + "." + args[1], null);
				saveConfig();
				
				Map<Long, ChunkLoadChunk> chunks = keep_loaded.get(w);
				if(chunks != null)
				{
					for(int x = xmin ; x <= xmax; x++) for(int z = zmin; z <= zmax; z++)
					{
						ChunkLoadChunk clc = chunks.get(coords2long(x, z));
						clc.removeRegion(r);
						if(clc.getRegions().size() <= 0) {chunks.remove(world.getChunkAt(x, z));}
					}
				}
				
				sender.sendMessage(ChatColor.YELLOW + "Region '" + args[1] + "' removed");
			}
			else {sender.sendMessage(ChatColor.RED + "Region '" + args[1] + "' doesn't exist in this world!");}
			return true;
		}
		else if((args[0].equals("select") || args[0].equals("s")) && args.length >= 2)
		{
			if(!acceptableName(args[1]))
			{
				sender.sendMessage(ChatColor.RED + "Invalid region name!");
			}
			else if(conf.contains("worlds." + w + "." + args[1]))
			{
				ConfigurationSection r = conf.getConfigurationSection("worlds." + w + "." + args[1]);
				int xmin = r.getInt("xmin");
				int xmax = r.getInt("xmax");
				if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;} 
				int zmin = r.getInt("zmin");
				int zmax = r.getInt("zmax");
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
			if(conf.getConfigurationSection("worlds." + w) == null)
			{
				sender.sendMessage(ChatColor.YELLOW + "No regions in this world.");
				return true;
			}
			ConfigurationSection wc = conf.getConfigurationSection("worlds." + w);
			Set<String> regions = wc.getKeys(false);
			if(regions.size() == 0)
			{
				sender.sendMessage(ChatColor.YELLOW + "No regions in this world.");
				return true;
			}
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
				if(r.equals("__global__"))
				{
					sender.sendMessage((start + done - 1) + ": " + ChatColor.YELLOW + r + " " + (wc.getBoolean(r + ".config.inactive")? ChatColor.AQUA + " - inactive" : ""));
				}
				else
				{
					int xmin = wc.getInt(r + ".xmin");
					int xmax = wc.getInt(r + ".xmax");
					if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;} 
					int zmin = wc.getInt(r + ".zmin");
					int zmax = wc.getInt(r + ".zmax");
					if(zmax < zmin) {int tmp = zmax; zmax = zmin; zmin = tmp;}
					sender.sendMessage((start + done - 1) + ": " + ChatColor.YELLOW + r + ": " + coords(xmin, xmax, zmin, zmax) + (wc.getBoolean(r + ".config.inactive")? ChatColor.AQUA + " - inactive" : ""));
				}
				done++;
				if(done >= 10) {break;}
			}
			if(done == 0) {sender.sendMessage(ChatColor.YELLOW + "There are only " + (start - done) + " regionsd defined.");}
			return true;
		}
		else if((args[0].equals("configure") || args[0].equals("config") || args[0].equals("c")) && args.length >= 2)
		{
			if(!acceptableName(args[1]))
			{
				sender.sendMessage(ChatColor.RED + "Invalid region name!");
			}
			else if(conf.contains("worlds." + w + "." + args[1]))
			{
				ConfigurationSection rc;
				if(!conf.contains("worlds." + w + "." + args[1] + ".config")) {rc = conf.getConfigurationSection("worlds." + w + "." + args[1]).createSection("config");}
				else {rc = conf.getConfigurationSection("worlds." + w + "." + args[1] + ".config");}
				
				if(args.length == 2)
				{//show configuration
					ConfigurationSection r = conf.getConfigurationSection("worlds." + w + "." + args[1]);
					int xmin = r.getInt("xmin");
					int xmax = r.getInt("xmax");
					if(xmax < xmin) {int tmp = xmax; xmax = xmin; xmin = tmp;} 
					int zmin = r.getInt("zmin");
					int zmax = r.getInt("zmax");
					if(zmax < zmin) {int tmp = zmax; zmax = zmin; zmin = tmp;}
					sender.sendMessage(ChatColor.YELLOW + args[1] + ": " + coords(xmin, xmax, zmin, zmax));
					
					String msg = "";
					for(String o: regionoptions) {if(rc.getBoolean(o)) {msg += ", " + o;}}
					sender.sendMessage(ChatColor.YELLOW + "Configuration: " + ChatColor.AQUA + (msg.length() > 0? msg.substring(2) : "default"));
				}
				else if(args.length == 3)
				{//delete option
					if(args[2].charAt(0) == '-' && Arrays.binarySearch(regionoptions, args[2].substring(1)) >= 0)
					{
						rc.set(args[2].substring(1), null);
						sender.sendMessage(ChatColor.YELLOW + "Option " + args[2].substring(1) + " cleared.");
					}
					else if(Arrays.binarySearch(regionoptions, args[2]) >= 0)
					{
						sender.sendMessage(ChatColor.YELLOW + "Option " + args[2] + " is " + (rc.contains(args[2])? "set to " + rc.getBoolean(args[2]) + "." : "not set."));
					}
					else {sender.sendMessage(ChatColor.RED + "No such option!");}
				}
				else
				{//set option
					if(Arrays.binarySearch(regionoptions, args[2]) >= 0)
					{
						if(args[3].equalsIgnoreCase("default"))
						{
							rc.set(args[2], null);
							sender.sendMessage(ChatColor.YELLOW + "Option " + args[2] + " cleared.");
						}
						else
						{
							rc.set(args[2], Boolean.parseBoolean(args[3]));
							sender.sendMessage(ChatColor.YELLOW + "Option " + args[2] + " set to " + rc.getBoolean(args[2]));
						}
					}
					else {sender.sendMessage(ChatColor.RED + "No such option!");}
				}
				saveConfig();
			}
			else {sender.sendMessage(ChatColor.RED + "Region '" + args[1] + "' doesn't exist in this world!");}
			return true;
		}
		else if(args[0].equals("options"))
		{
			String msg = "";
			for(String o : regionoptions) {msg += ", " + o;}
			sender.sendMessage(ChatColor.YELLOW + "Available Options:" + ChatColor.AQUA + msg.substring(1));
			return true;
		}
		else if(args[0].equals("reload"))
		{
			//keep_loaded.clear();
			//loadData();
			sender.sendMessage(ChatColor.RED + "This doesn't work yet...");
			return true;
		}
		
		return false;	
	}
	
	@EventHandler
	public void onChunkUnload(ChunkUnloadEvent e)
	{
		// Get relevant data from the event
		String w = sanitizeName(e.getWorld().getName());
		final Chunk c = e.getChunk();
		
		// check if chunk can unload via rectangles or a chunk list
		// cancel the unload event if it's not supposed to unload
		if(keep_loaded.get(w) != null && keep_loaded.get(w).containsKey(chunk2long(c)))
		{
			ChunkLoadChunk clc = keep_loaded.get(w).get(chunk2long(c));
			for(ConfigurationSection r : clc.getRegions())
			{
				if(!r.getBoolean("config.inactive"))
				{
					e.setCancelled(true);
					getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable()
						{
							@Override
							public void run()
							{
								c.load(false);
							}
						}
						, 1);
					return;
				}
			}
		}
	}
	
	@EventHandler
	public void onWorldInit(WorldInitEvent e)
	{
		World world = e.getWorld();
		loadData(sanitizeName(world.getName()), world);
	}
	
	private static String sanitizeName(String w)
	{
		return w.replaceAll("\\.", "");
	}
	
	private static boolean acceptableName(String s)
	{
		return s.indexOf('.') == -1 && (s.substring(0, 2).equals("__") == (Arrays.binarySearch(specialregions, s) >= 0));
	}
	
	private static String coords(int xmin, int xmax, int zmin, int zmax)
	{
		return "(" + (xmin * 16) + "," + (zmin * 16) + ") (" + (xmax * 16 + 15) + "," + (zmax * 16 + 15) + ") - " + ((xmax - xmin + 1) * (zmax - zmin + 1)) + " Chunks";
	}
	
	private static long chunk2long(Chunk c)
	{
		return (long) c.getX() << Integer.SIZE | c.getZ();
	}
	
	private static long coords2long(int x, int z)
	{
		return (long) x << Integer.SIZE | z;
	}
}
