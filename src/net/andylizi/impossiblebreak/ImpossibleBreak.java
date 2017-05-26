/*
 * The MIT License (MIT)
 *
 * Copyright(c) 2017 andylizi.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.andylizi.impossiblebreak;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * @author andylizi
 */
public final class ImpossibleBreak extends JavaPlugin
    implements Listener{
    
    private static final ThreadLocal<Location> LOCATION_SUPPLIER = ThreadLocal.withInitial(() -> new Location(null, 0, 0, 0));
    private static final ThreadLocal<HashSet> SET_SUPPLIER = ThreadLocal.withInitial(HashSet::new);

    private UUID lastBreak;
    private long lastBreakTime;
    private Material lastBreakType;
    
    private final EnumSet<Material> checkTypes = EnumSet.of(Material.BED_BLOCK);
    private int searchDepth = 10;
    private double maxDistance = 12;
    private boolean enableCheckBreakSpeed = true;
    private double maxBreakSpeed = 100;

    private String msg_broadcast_impossibleBreak;
    private String msg_broadcast_fastBreak;
    private String msg_kick_impossibleBreak;
    private String msg_kick_fastBreak;
    
    /**
     * 检查区域密封状态. 
     * @param block 起始方块
     * @param center 中心位置 (用于判定是否超出最远距离限制)
     * @param checked 已递归到的方块列表
     */
    protected Set<Block> checkSealed0(Block block, Location center, Set<Block> checked){
        if(checked == null){
            checked = new HashSet<>();
        }
        Location loc = LOCATION_SUPPLIER.get();
        for(BlockFace face : BlockChecker.isBreathable(block)){
            if(checked.size() > searchDepth)   // 递归深度如果大于配置文件设置, 则退出递归
                return checked;
            
            Block b = block.getRelative(face);   // 获得透气面旁边的方块
            if(BlockChecker.checkSolid(b.getType()))
                continue;
            if(!checked.add(b))
                continue;

            // DEBUG
            // b.getWorld().playEffect(b.getLocation(), Effect.SMOKE, BlockFace.SELF);

            if(b.getLocation(loc).distanceSquared(center) < maxDistance){
                checkSealed0(b, center, checked);  // 如果没有超过最远距离限制, 则进行递归检测
            }
        }
        return checked;
    }
    
    /** 检查从指定玩家所在的位置, 能否与指定方块交互. */
    public boolean checkImpossibleInteract(Player player, Block block){
        Set<Block> reached = SET_SUPPLIER.get();
        reached.clear();
        try{
            checkSealed0(block, block.getLocation(), reached);
            // player.sendMessage("搜索深度: " + reached.size()); // DEBUG
            if(reached.size() < searchDepth){
                Block eyeBlock = player.getEyeLocation().getBlock();
                return !reached.contains(eyeBlock);  // 区域密封了, 这时根据玩家头部是否在区域内来判定
            }else return false;   // 超出了搜索深度, 视为区域未密封, 允许玩家交互. 
        }finally{
            reached.clear();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event){
        Block block = event.getBlock();
        Material type = block.getType();
        if(checkTypes.contains(type)){
            Player player = event.getPlayer();
            if(enableCheckBreakSpeed && 
                    checkFastBreak(player, type)){
                event.setCancelled(true);
            }else if(checkImpossibleInteract(player, block)){
                event.setCancelled(true);
                player.kickPlayer(msg_kick_impossibleBreak);
                if(msg_broadcast_impossibleBreak != null)
                    Bukkit.broadcastMessage(String.format(msg_broadcast_impossibleBreak, 
                            player.getCustomName() == null ? player.getPlayerListName() : player.getCustomName()));
            }
        }
    }
    
    private boolean checkFastBreak(Player player, Material type){
        long now = System.currentTimeMillis();
        UUID uid = player.getUniqueId();
        try{
            if(type == lastBreakType && 
                    uid.equals(lastBreak) &&
                    now - lastBreakTime < maxBreakSpeed){
                player.kickPlayer(msg_kick_fastBreak);
                if(msg_broadcast_fastBreak != null)
                    Bukkit.broadcastMessage(String.format(msg_broadcast_fastBreak, 
                            player.getCustomName() == null ? player.getPlayerListName() : player.getCustomName()));
                return true;
            }
        }finally{
            lastBreak = uid;
            lastBreakTime = now;
            lastBreakType = type;
        }
        return false;
    }

    // <editor-fold defaultstate="collapsed" desc="插件加载">
    @Override
    public void onEnable() {
        loadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    
    @SuppressWarnings("deprecation")
    private void loadConfig(){
        saveDefaultConfig();
        reloadConfig();
        checkTypes.clear();
        getConfig().getStringList("checkImpossibleBreak.checkTypes").forEach(str -> {
            try{
                checkTypes.add(Material.getMaterial(Integer.valueOf(str)));
            } catch(NumberFormatException ex){
                Material type = Material.matchMaterial(str);
                if(type == null){
                    getLogger().warning(String.format("Unknown type \"%s\"", str));
                }else checkTypes.add(type);
            }
        });
        checkTypes.removeIf(type -> {
            if(!type.isBlock()){
                getLogger().warning(String.format("Type \"%s\" is not a block!", type.toString()));
                return true;
            }
            return false;
        });
        getLogger().info(String.format("Check types: %s", checkTypes));
        searchDepth = getConfig().getInt("checkImpossibleBreak.searchDepth");
        maxDistance = getConfig().getDouble("checkImpossibleBreak.maxDistance");
        maxDistance *= maxDistance;  // 因为使用的距离函数为省去平方根运算的 distanceSquared() 而非 distance()
        enableCheckBreakSpeed = getConfig().getBoolean("checkBreakSpeed.enable");
        maxBreakSpeed = getConfig().getDouble("checkBreakSpeed.maxBreakSpeed");

        ConfigurationSection msg = getConfig().getConfigurationSection("messages");
        msg_broadcast_impossibleBreak = color(msg.getString("broadcast.impossibleBreak"));
        msg_broadcast_fastBreak = color(msg.getString("broadcast.fastBreak"));
        msg_kick_impossibleBreak = color(msg.getString("kick.impossibleBreak", ""));
        msg_kick_fastBreak = color(msg.getString("kick.fastBreak", ""));
        if(msg_kick_impossibleBreak == null)
            msg_kick_impossibleBreak = "";
        if(msg_kick_fastBreak == null)
            msg_kick_fastBreak = "";
    }
    
    private static String color(String str){
        if(str == null)
            return null;
        return ChatColor.translateAlternateColorCodes('&', str);
    } // </editor-fold>                        
}
