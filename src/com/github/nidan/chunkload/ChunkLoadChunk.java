package com.github.nidan.chunkload;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

public class ChunkLoadChunk
{
	World world;
	int x;
	int z;
	HashSet<ConfigurationSection> configs;
	
	ChunkLoadChunk(World w, Chunk c)
	{
		world = w;
		x = c.getX();
		z = c.getZ();
		configs = new HashSet<ConfigurationSection>();
	}
	
	protected void addRegion(ConfigurationSection conf)
	{
		configs.add(conf);
	}
	
	protected void removeRegion(ConfigurationSection conf)
	{
		configs.remove(conf);
	}

	protected Set<ConfigurationSection> getRegions()
	{
		return configs;
	}
	
	public Chunk getChunk()
	{
		return world.getChunkAt(x, z);
	}
}
