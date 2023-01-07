# CouldPickUpItemWatcher

This little library helps you to detect the potential of an item pickup when the player
in question doesn't have any more space in their inventory to actually perform that action.
You can perform a fake pickup, including playing the animation as well as removing the item
from the world by acquiring that item through making use of the custom event this lib offers.

## Example

Register all events for the class you're planning to listen for this event in and initialize
the pickup watcher by calling it's constructor while providing a reference to your `Plugin`.

```java
@Override
public void onEnable() {
  getServer().getPluginManager().registerEvents(this, this);

  try {
    new CouldPickUpItemWatcher(this);
  } catch (Exception e) {
    e.printStackTrace();
    Bukkit.getPluginManager().disablePlugin(this);
  }
}
```

This constructor will throw if it didn't manage to find the required handles (classes, methods, fields),
which in indicates that the server version causes the need of some further attention in order to get
this library to work. It should be delivering it's promises at least all through `1.8.8` - `1.19.3`.

Subscribe to the event and act according to your needs. As soon as you acquire the item, it's claimed for
that player. The animation will be played towards the player and no other player may interact with the item
until it's been removed from the world.

```java
@EventHandler
public void onCouldPickup(PlayerCouldPickupItemEvent e) {
  ItemStack item = e.getItem().getItemStack();
  Player player = e.getPlayer();

  // TODO: Check if Player player is allowed to pickup Item item with a full inventory

  // Setting the state of this event to acquired will acquire this
  // item for the current player, play the pickup-animation and remove
  // it from the world. By not calling this method with true, the player
  // will not be picking up this item. This allows for selective pickup,
  // based on permissions, for example.
  e.setAcquired(true);

  // TODO: Handle storing Item item for the Player player
}
```