_Do you hate wasting time manually crafting microcomponents in tech modpacks? Do you wish that you could just craft an item directly without crafting its intermediate steps? I know I do, so thats why I made Fabricate._


# What does Fabricate do?

Fabricate is a crafting extension for JEI/EMI that lets you craft items directly from base materials.

### What does this mean?

Picture this. You have one log in your inventory, and you want to craft that log into a wooden shovel at a crafting table. Normally, you'd have to craft the log into planks, then those planks into sticks, then assemble both materials into a shaped crafting recipe to get a wooden shovel. 

With Fabricate, you click on the icon of a wooden shovel in the EMI/JEI menu, then immediately get a wooden shovel with no extra steps. The mod does all the intermediate steps for you, and returns any leftovers from the craft directly to your inventory.


### How does it work?

When you click an item in JEI or EMI's sidebar, Fabricate looks at what's actually in your inventory and walks the recipe graph backwards from the item you clicked. It treats anything you already have as a usable starting point, recurses through whatever intermediate recipes are needed (planks → sticks → shovel), and returns a single plan that says "take these raw materials, run these recipe steps, here's the item plus any leftover refunds."

That planning happens server-side every time you click to craft an item via EMI or JEI. The plan respects your current crafting grid (a 3-row recipe like wooden shovel won't plan from your 2x2 inventory grid, only at a crafting table), uses what's already in your inventory before crafting more intermediates, and refunds anything left over so you never lose materials to over-batching.

Since Fabricate only handles crafting, you cannot use the mod to automatically smelt iron ore into iron ingots, or logs into charcoal. Any crafts that require a separate process do not work this way.


### How do I use it?

Install JEI or EMI (or both) and drop Fabricate into your mods folder. When in a world, open your inventory or a crafting table and find the item you want in the recipe viewer's sidebar.  If you can craft the item from the materials you have on hand, clicking will automatically craft the item and insert any leftovers directly into your inventory.


### Does it work with mods?

In theory, yes. Fabricate just reads whatever crafting recipes are loaded when the world starts. Any mod that adds new crafting recipes (Create, Quark, Supplementaries, content mods of any kind) should be supported automatically.

