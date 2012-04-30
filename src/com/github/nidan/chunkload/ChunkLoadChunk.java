package com.github.nidan.chunkload;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Chunk;
import org.bukkit.configuration.ConfigurationSection;

public class ChunkLoadChunk
{
	Chunk chunk;
	HashSet<ConfigurationSection> configs;
	
	ChunkLoadChunk(Chunk c)
	{
		chunk = c;
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
		return chunk;
	}
}
