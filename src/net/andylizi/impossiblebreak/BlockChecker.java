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

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import static org.bukkit.block.BlockFace.*;
import org.bukkit.block.BlockState;
import org.bukkit.material.*;

/**
 * 对一个区域进行密封检查. 
 * <p>
 * 
 * <dl>
 *   <dt>透气</dt>
 *     <dd>
 *       此方块至少一个面的碰撞箱不是完整的, 即为"透气"的方块. <p>
 *       换句话说, 玩家指针可以不受影响的穿过透气的方块, 跟其他方块交互. <p>
 *       典型例子如 {@linkplain Material#STEP 台阶}, 它除了底面外其他面均为透气面. 
 *     </dd>
 *   <dt>密封</dt>
 *     <dd>即一个区域被不透气的方块所包围, 玩家指针无法与区域内的方块与实体交互. </dd>
 * </dl>
 * @author andylizi
 */
public final class BlockChecker {
    /** <code>东 西 南 北</code> 四面的集合. */
    private static final BlockFace[] ROUND_FACES = new BlockFace[]{ EAST, WEST, SOUTH, NORTH };

    /** {@link BlockFace} 空集. */
    private static final EnumSet<BlockFace> NONE_FACE = EnumSet.noneOf(BlockFace.class);
    
    /** <code>上 下</code> 两面的集合. */
    private static final EnumSet<BlockFace> UP_AND_DOWN = add(NONE_FACE, UP, DOWN);
    
    /** 全部六个面的集合. */
    private static final EnumSet<BlockFace> ALL_FACE = add(UP_AND_DOWN, ROUND_FACES);
    
    /**
     * <code>东 南 西 北 上</code> 五个面的集合. 
     * 等同于 {@link #ALL_FACE} 与 {@link BlockFace#DOWN} 的差集. 
     */
    private static final EnumSet<BlockFace> ALL_FACE_EXCEPT_DOWN = subtract(ALL_FACE, DOWN);
    
    /** Bukkit 认为透气但其实不透气的方块类型. */
    private static final EnumSet<Material> OTHER_SOLID_BLOCK;
    
    /** 只有底座不透气(有完整碰撞箱)的方块类型. */
    private static final EnumSet<Material> HAS_BASE_BLOCK;
    
    /** 永远返回 {@link #ALL_FACE}. */
    private static final Supplier<Function<MaterialData, EnumSet<BlockFace>>> ALL_FACE_SUPPLIER = 
            () -> d -> ALL_FACE;
    
    /** 存放用于处理特殊方块的 handler. */
    private static final Map<Class<? extends MaterialData>, 
            Function<MaterialData, EnumSet<BlockFace>>> SPECIAL_CASE_HANDLER_MAP;

    static{
        {
            /* Bukkit 认为透气但其实不透气的方块类型. */
            EnumSet<Material> set = OTHER_SOLID_BLOCK = EnumSet.noneOf(Material.class);
            set.add(Material.GLASS);                    // 玻璃
            set.add(Material.STAINED_GLASS);            // 染色玻璃
            set.add(Material.WEB);                      // 蜘蛛网
            set.add(Material.LEAVES);                   // 树叶
            set.add(Material.LEAVES_2);                 // 树叶
            set.add(Material.DOUBLE_PLANT);             // 双层植物
            set.add(Material.HOPPER);                   // 漏斗
            set.add(Material.SEA_LANTERN);              // 海晶灯
            set.add(Material.GLOWSTONE);                // 萤石
            set.add(Material.ICE);                      // 冰
        }

        {
            /* 只有底座不透气的方块类型 */
            EnumSet<Material> set = HAS_BASE_BLOCK = EnumSet.noneOf(Material.class);
            set.add(Material.BED_BLOCK);             // 床
            set.add(Material.SNOW);                  // 雪片
            set.add(Material.ENCHANTMENT_TABLE);     // 附魔台
            set.add(Material.ENDER_PORTAL_FRAME);    // 末地传送门框架
            set.add(Material.CARPET);                // 地毯
            set.add(Material.WATER_LILY);            // 睡莲
            set.add(Material.DAYLIGHT_DETECTOR);     // 阳光传感器
            set.add(Material.DAYLIGHT_DETECTOR_INVERTED);
            set.add(Material.REDSTONE_WIRE);         // 红石线
            set.add(Material.DIODE_BLOCK_ON);        // 中继器
            set.add(Material.DIODE_BLOCK_OFF);       
            set.add(Material.REDSTONE_COMPARATOR_ON);// 比较器
            set.add(Material.REDSTONE_COMPARATOR_OFF);
            set.add(Material.RAILS);                 // 铁轨
            set.add(Material.POWERED_RAIL);          // 充能铁轨
            set.add(Material.ACTIVATOR_RAIL);        // 激活铁轨
            set.add(Material.DETECTOR_RAIL);         // 探测铁轨
        }
        
        {
            /* 注册处理特殊方块的 handler */
            Map<Class<? extends MaterialData>, 
                    Function<MaterialData, EnumSet<BlockFace>>> map = new HashMap<>();

            map.put(Stairs.class, data -> {              // 楼梯
                Stairs stair = (Stairs) data;
                BlockFace stairFace = stair.getAscendingDirection();
                return removeIf(ALL_FACE, face -> 
                        face == stairFace || 
                        face == (stair.isInverted() ? UP : DOWN));
            });
            
            map.put(Step.class, data -> {                // 台阶
                Step step = (Step) data;
                return subtract(ALL_FACE, step.isInverted() ? UP : DOWN);
            });
            
            map.put(WoodenStep.class, data -> {          // 木台阶
                WoodenStep step = (WoodenStep) data;
                return subtract(ALL_FACE, step.isInverted() ? UP : DOWN);
            });
            
            map.put(Ladder.class, data -> {              // 梯子
                Ladder ladder = (Ladder) data;
                return subtract(ALL_FACE, ladder.getAttachedFace());
            });
            
            map.put(Vine.class, data -> {                // 藤蔓
                Vine vine = (Vine) data;
                BlockFace blocked = null;
                for(BlockFace face : ROUND_FACES){
                    if(vine.isOnFace(face)){
                        if(blocked != null)
                            return NONE_FACE;
                        else blocked = face;
                    }
                }
                return subtract(ALL_FACE, blocked);
            });
            
            map.put(PistonBaseMaterial.class, data -> {  // 活塞
                PistonBaseMaterial pistonBase = (PistonBaseMaterial) data;
                if(!pistonBase.isPowered())
                    return NONE_FACE;
                BlockFace poweredFace = pistonBase.getFacing().getOppositeFace();
                return subtract(ALL_FACE, poweredFace);
            });
            
            map.put(WoodenStep.class, data -> {          // 活塞杆
                PistonExtensionMaterial pistonExtension = (PistonExtensionMaterial) data;
                return subtract(ALL_FACE, pistonExtension.getFacing());
            });
            
            map.put(TrapDoor.class, data -> {            // 活板门
                TrapDoor trapDoor = (TrapDoor) data;
                if(trapDoor.isOpen()){
                    return subtract(ALL_FACE, trapDoor.getAttachedFace());
                }else{
                    return subtract(ALL_FACE, trapDoor.isInverted() ? UP: DOWN);
                }
            });
            
            map.put(WoodenStep.class, data -> {          // 栅栏门
                Gate gate = (Gate) data;
                return add(UP_AND_DOWN, gate.getFacing(), gate.getFacing().getOppositeFace());
            });
            
            map.put(Door.class, data -> {                // 门
                return ALL_FACE;  // 门的模型太复杂了, 所以我偷了个懒, 按完全透气处理了 ：[
            });

            SPECIAL_CASE_HANDLER_MAP = Collections.unmodifiableMap(map);
        }
    }
    
    /**
     * 获取方块的所有透气面. 
     * @param block 方块 {@link Block}
     * @return 此方块在当前状态下的所有透气面
     */
    public static EnumSet<BlockFace> isBreathable(Block block){
        Material type = block.getType();
        if(!checkSolid(type)){
            return getBreathableFace(type, block.getState());
        }else return NONE_FACE;  // 如果这个方块是全密封方块, 则直接返回空集
    }
    
    /**
     * 检查此材料是否为全密封方块. 
     * @param type 方块类型, 符合 {@link Material#isBlock()}
     * @return 是否为全密封方块
     */
    public static boolean checkSolid(Material type){
        return type.isOccluding() || OTHER_SOLID_BLOCK.contains(type);
    }
    
    /** 在调用此方法记得先 {@link #checkSolid(Material)}. */
    private static EnumSet<BlockFace> getBreathableFace(Material type, BlockState state){
        if(HAS_BASE_BLOCK.contains(type))
            return ALL_FACE_EXCEPT_DOWN;    // 偷懒, 省去查找步骤
        
        MaterialData data = state.getData();
        if(type == Material.ANVIL){  // 铁砧是一个 very special case, 因此单独处理
            @SuppressWarnings("deprecation")
            int d = data.getData();
            switch(d){
                case 0:
                case 2:
                    return add(UP_AND_DOWN, NORTH, SOUTH);
                case 1:
                case 3:
                    return add(UP_AND_DOWN, EAST, WEST);
                default: return ALL_FACE;
            }
        }
        
        Class<? extends MaterialData> dataType = type.getData();
        Optional<Function<MaterialData, EnumSet<BlockFace>>> optional = 
                SPECIAL_CASE_HANDLER_MAP.entrySet().stream()
                .filter(entry -> entry.getKey().isAssignableFrom(dataType))  // 匹配 MaterialData 对应的 handler
                .findFirst()                // 获取第一个匹配到的 handler
                .map(Map.Entry::getValue);  // 取其 value
        return optional.orElseGet(ALL_FACE_SUPPLIER)  // 如果匹配找到结果, 则判定为六面都透气
                .apply(data);  
    }
    
    // <editor-fold defaultstate="collapsed" desc="Utility methods">
    private static <E extends Enum<E>> EnumSet<E> removeIf(EnumSet<E> set, Predicate<E> predicate){
        EnumSet<E> clone = set.clone();
        clone.removeIf(predicate);
        return clone;
    }
    
    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> EnumSet<E> subtract(EnumSet<E> set, E... element){
        EnumSet<E> clone = set.clone();
        clone.removeAll(Arrays.asList(element));
        return clone;
    }
    
    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> EnumSet<E> add(EnumSet<E> set, E... element){
        EnumSet<E> clone = set.clone();
        clone.addAll(Arrays.asList(element));
        return clone;
    }// </editor-fold>   
    
    private BlockChecker() throws AssertionError { throw new AssertionError(); }
}
