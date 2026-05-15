_Do you hate wasting time manually crafting microcomponents in tech modpacks? Do you wish that you could just craft an item directly without crafting its intermediate steps? I know I do, so thats why I made Fabricate._


## What does Fabricate do?

Fabricate is a crafting extension for JEI/EMI that lets you craft items directly from base materials.

***What does this mean?***

Picture this. You have one log in your inventory, and you want to craft that log into a wooden shovel at a crafting table. Normally, you'd have to craft the log into planks, then those planks into sticks, then assemble both materials into a shaped crafting recipe to get a wooden shovel. 

With Fabricate, you click on the icon of a wooden shovel in the EMI/JEI menu, then immediately get a wooden shovel with no extra steps. The mod does all the intermediate steps for you, and returns any leftovers from the craft directly to your inventory.

***How do I use it?***

Install JEI or EMI (or both) and drop Fabricate into your mods folder. When in a world, open your inventory or a crafting table and find the item you want in the recipe viewer's sidebar.  If you can craft the item from the materials you have on hand, clicking will automatically craft the item and insert any leftovers directly into your inventory. If not, nothing happens.

***How does it work?***

Upon joining a world, Fabricate takes every crafting recipe ingame and processes their inputs and outputs.

It walks through every recipe, tracing its ingredients to whatever those ingredients are made from, and so on, until it lands on items you actually obtain through gameplay like logs, leather, iron ingots, etc. Anything craftable along the way is treated as an "intermediate" that Fabricate remembers how to make so you don't have to.

The result is that Fabricate makes a set of shortcut recipes. For example, a wooden shovel keeps its 1-plank-and-2-sticks recipe, but Fabricate also adds a sibling recipe that takes 1 log directly.

Since Fabricate only cares about basic crafting, you cannot use the mod to automatically smelt iron ore into iron ingots, or logs into charcoal. Any crafts that require a separate process to make do not get crafted in one click.

***What about leftovers?***

Crafting often produces more of an intermediate than you actually need. Fabricate tracks every byproduct along the way and returns any extra materials to your inventory the moment the craft finishes. You end up with exactly what you'd have if you'd done every step manually.

***Which recipe gets picked?***

If multiple recipes can produce the same item, Fabricate ranks them by how close they are to vanilla and how few materials they need, then picks the cheapest one your inventory can actually afford.

***Does it work with mods?***

In theory, yes. Fabricate just reads whatever crafting recipes are loaded when the world starts. Any mod that adds new crafting recipes (Create, Quark, Supplementaries, content mods of any kind) should be supported automatically.
