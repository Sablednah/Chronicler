# Permissions and access

Chronicler registers **one** permission node through NeoForge's `PermissionAPI`,
so SableCraft Standards' handler and LuckPerms both manage it.

| Node | Default | What it allows |
|---|---|---|
| `chronicler.admin` | nobody; **op level 2 also passes** | the `/chronicler` tree (`reload`, `status`, `journal <player>`, `flag`, `reset <player>`) and `/quest giver set|remove|list` |

Everything under `/quest` is open to every player and carries no node.

The default resolver returns `false`, so a server that installs a permission
manager and grants nothing behaves exactly as before: ops keep the admin tree
through their op level, nobody else has it.
