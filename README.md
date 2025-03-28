# 🐰 MadHarePlugin

A chaotic, high-energy Spigot plugin that introduces **The Mad Hare**, a custom boss battle designed for special events, Easter-themed madness, or just pure bunny chaos.

---

## ⚔️ Features

- **Custom Boss Fight:** Summon "The Mad Hare", a giant rabbit boss with scaling health and multiple special abilities.
- **Boss Abilities:**
  - 🥕 **Carrot Missile**: Fires homing carrots that damage and debuff players.
  - 🕳️ **Burrow**: Disappears underground and reemerges dramatically.
  - 🐇 **Mini Bunny Swarm**: Summons mini rabbits with Slowness and scalable numbers.
  - 💓 **Healing Phase**: Attempts to heal unless interrupted by damage.
- **Live Scoreboard HUD:**
  - Displays boss health, number of mini rabbits, and top 5 damage dealers.
  - Styled with animations and auto-hides 30 seconds after boss defeat.
- **Damage Tracking:** Tracks total damage dealt by players to both the boss and its minions.
- **Top Player Rewards:**
  - 🎖 Top 5 players receive a custom iron helmet with Jump Boost III and victory lore.
  - 🥚 Boss drops a single "Egg of Resurrection" as loot.
- **Fully Configurable:** Modify ability frequency, effects, scaling, potion types, messages, and more via `config.yml`.
- **Permission-based Commands:**
  - `/summonmad` requires `madhare.summon`
  - `/madreload` requires `madhare.reload`

---

## 📦 Installation

1. Build using Maven or download the compiled JAR from [releases](#).
2. Drop the plugin into your server's `/plugins` folder.
3. Start or reload the server.
4. Configure as needed in `config.yml`.

---

## 🔧 Commands

| Command | Description | Permission |
|--------|-------------|------------|
| `/summonmad` | Summons the Mad Hare at your location | `madhare.summon` |
| `/madreload` | Reloads the plugin config | `madhare.reload` |

---

## ⚙️ Configuration Overview (`config.yml`)

```yaml
# [See full configuration in actual config.yml file]
```

---

## 🧙 Built With

- [Spigot API](https://www.spigotmc.org/)
- Java 17
- Maven

---

## 📜 License

MIT — Use freely and modify as needed!

---

## 💡 Tips

- For multi-world setups, use `/mv tp` or similar to place the boss.
- Customize the lore, effects, and visuals for your server’s theme.
- Great for Easter events, chaotic raids, or special boss nights.

---

## 🐣 Enjoy the chaos!
