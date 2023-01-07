/*
 * MIT License
 *
 * Copyright (c) 2023 BlvckBytes
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package me.blvckbytes.couldpickupitemwatcher;

import me.blvckbytes.bbreflect.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;

public class CouldPickUpItemWatcher implements Listener {

  // https://minecraft.fandom.com/wiki/Hitbox -> List of entity hitboxes -> Player
  // cuboid of height=1.8, width=0.6
  private static final double HITBOX_WIDTH = .6, HITBOX_HEIGHT = 1.8;

  // vanilla bounding box inflation: this.getBoundingBox().inflate(1.0D, 0.5D, 1.0D);
  // Inflating a bounding-box means to subtract x/y/z from min and add it to max, thus
  // expanding the bounding-box by twice the value provided on each axis
  private static final double INFLATE_X  = 1.0, INFLATE_Y = 0.5, INFLATE_Z = 1.0;

  // Items which have already been acquired by a player and are now in the
  // process of being picked up and destroyed afterwards. These items will not trigger
  // any further event calls and be blocked from being picked up.
  private final Map<Item, Player> acquiredItems;

  private final FieldHandle F_CRAFT_PLAYER__HANDLE, F_ITEM__HANDLE;
  private final MethodHandle M_ENTITY_PLAYER__TAKE_ENTITY;

  /**
   * Create a new watcher which periodically checks on each server tick if any
   * player is near an item they cannot pick up anymore (full inventory) in order
   * to then call the {@link PlayerCouldPickupItemEvent}
   * @param plugin Plugin reference used to register the task timer as well as the listener
   */
  public CouldPickUpItemWatcher(Plugin plugin) throws Exception {
    this.acquiredItems = new WeakHashMap<>();

    ReflectionHelper rh = new ReflectionHelper(null);

    ClassHandle C_CRAFT_PLAYER = rh.getClass(RClass.CRAFT_PLAYER);
    ClassHandle C_CRAFT_ITEM = rh.getClass(RClass.CRAFT_ITEM);
    ClassHandle C_ENTITY_PLAYER = rh.getClass(RClass.ENTITY_PLAYER);
    ClassHandle C_ENTITY = rh.getClass(RClass.ENTITY);

    // CraftPlayer wrapper's NMS handle
    F_CRAFT_PLAYER__HANDLE = C_CRAFT_PLAYER
      .locateField()
      .withType(C_ENTITY, false, Assignability.TYPE_TO_TARGET)
      .withAllowSuperclass(true)
      .required();

    // CraftItem wrapper's NMS handle
    F_ITEM__HANDLE = C_CRAFT_ITEM
      .locateField()
      .withType(C_ENTITY, false, Assignability.TYPE_TO_TARGET)
      .required();

    // public void take(Entity entity, int i);
    // Plays the collect animation for all players within the same chunk for the given entity and amount
    M_ENTITY_PLAYER__TAKE_ENTITY = C_ENTITY_PLAYER
      .locateMethod()
      .withParameter(C_ENTITY)
      .withParameter(int.class)
      .withPublic(true)
      .required();

    // Check all players for possible item pickups on each tick
    Bukkit.getScheduler().runTaskTimer(plugin, () -> {
      for (Player p : Bukkit.getOnlinePlayers())
        processPlayer(p);
    }, 0, 0);

    Bukkit.getServer().getPluginManager().registerEvents(this, plugin);
  }

  // This event may be deprecated, but it's the only way of having cross-version
  // support, as the new entity-based event is unusable at lower versions
  @EventHandler
  public void onPickup(PlayerPickupItemEvent e) {
    // Disallow the pickup of acquired items
    if (acquiredItems.containsKey(e.getItem()))
      e.setCancelled(true);
  }

  /**
   * Called whenever a single player needs to be checked for the potential of pickups
   */
  private void processPlayer(Player p) {
    // Player#getLocation returns a location at x/z center of the player, but at their feet
    // Adding half of the hitbox height will shift the location into the x/y/z center of the player
    Location playerCenterCenter = p.getLocation().add(0, HITBOX_HEIGHT / 2, 0);

    // World#getNearbyEntities "inflates" around the provided center location symmetrically
    Collection<Entity> nearbyEntities = p.getWorld().getNearbyEntities(
      playerCenterCenter,
      HITBOX_WIDTH  / 2 + INFLATE_X, // dX (+-)
      HITBOX_HEIGHT / 2 + INFLATE_Y, // dY (+-)
      HITBOX_WIDTH  / 2 + INFLATE_Z  // dZ (+-)
    );

    for (Entity entity : nearbyEntities) {
      if (!(entity instanceof Item))
        continue;

      processItemPickup(p, (Item) entity);
    }
  }

  /**
   * Called whenever a player is near enough to the given item in order to be able to pick it up
   */
  private void processItemPickup(Player p, Item item) {
    // This item has already been acquired
    if (acquiredItems.containsKey(item))
      return;

    // Still has a pickup delay (this value is decreased internally when the item is being ticked)
    if (item.getPickupDelay() > 0)
      return;

    // Could pick it up, skip
    if (couldPickupAtLeastSome(p, item.getItemStack()))
      return;

    // Has the potential for picking this item up, if they would make some space in their inventory
    PlayerCouldPickupItemEvent event = new PlayerCouldPickupItemEvent(p, item);
    Bukkit.getPluginManager().callEvent(event);

    if (event.isAcquired())
      acquireItemFor(p, item);
  }

  /**
   * Called whenever a player acquired an item (fake pickup), which will add the item
   * to the local map of acquired items to reserve it for that given player. A pickup
   * animation is being distributed and the item is being removed afterwards.
   */
  private void acquireItemFor(Player p, Item item) {
    acquiredItems.put(item, p);

    try {
      Object entityPlayer = F_CRAFT_PLAYER__HANDLE.get(p);
      Object entityItem = F_ITEM__HANDLE.get(item);
      M_ENTITY_PLAYER__TAKE_ENTITY.invoke(entityPlayer, entityItem, item.getItemStack().getAmount());
    } catch (Exception e) {
      e.printStackTrace();
    }

    item.remove();
  }

  /**
   * Floors the provided inventory size to the next multiple of 9. This is
   * used to avoid looping non-storage-content slots like armor and shield
   * which are a problem at higher versions, where Inventory#getStorageContents
   * has been implemented to solve this, which is not available at lower versions.
   */
  private int floorInventorySize(int size) {
    return size - size % 9;
  }

  /**
   * Checks if the given player could pick up at least some (one or more)
   * of the provided item-stack
   */
  private boolean couldPickupAtLeastSome(Player p, ItemStack item) {
    Inventory inv = p.getInventory();

    for (int i = 0; i < floorInventorySize(inv.getSize()); i++) {
      ItemStack content = p.getInventory().getItem(i);

      // Empty slot, can pick up a whole stack
      if (content == null)
        return true;

      // Has no more stack space left
      if (content.getAmount() >= content.getMaxStackSize())
        continue;

      // Is not stackable with the target item
      if (!content.isSimilar(item))
        continue;

      // Has space and is stackable
      return true;
    }

    // Completely full of non-stackable items
    return false;
  }
}
