# Agentation — point at the broken thing instead of describing it

Click an element, type what is wrong, press `c`. You get markdown with the CSS selector, DOM
path and bounding box. Paste that to the agent. Off unless `SBOX_AGENTATION=true` is in the
**process** environment — no file, no profile and no committed config can turn it on.

**Turn it on** (PowerShell, from `C:\Users\WW\Desktop\sboxmarket`; needs a `.\gradlew.bat build -x test` first):

```powershell
$env:SBOX_AGENTATION="true"; $env:SERVER_PORT="8096"; $env:SPRING_DATASOURCE_URL="jdbc:h2:file:./data/sboxmarket;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
java -Dloader.path=build/classes/groovy/main,build/resources/main -cp build/libs/sboxmarket-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Then open <http://localhost:8096/> — a dark pill appears bottom-right.

**Use it:** `Ctrl+Shift+F` → click the element → type the complaint → **Add**. Repeat for every
problem on the page. Then press `c` and paste — the clipboard now holds the whole list.

**Turn it off:** close that PowerShell window. Nothing persists; a shell without the variable
serves no toolbar and `/__agentation/**` is a 404.

---

<details>
<summary>Details</summary>

- **Port 8096, not 8082.** `~/.cloudflared/config.yml` maps `skinbox.market -> localhost:8082`
  and a tunnel connects *from* loopback, so binding 8082 would publish this to the internet.
  8096 matches no ingress rule. Any free port other than 8082 is fine.
- **`--no-daemon` if you use `gradlew bootRun` instead.** With a Gradle daemon alive, `bootRun`
  forks the app from the *daemon's* environment — which predates your `$env:` line — and the
  toolbar silently never appears. Same trap as `SBOX_DEV_LOGIN_ENABLED`.
- **It makes no network requests.** No `endpoint` and no `webhookUrl` are configured, so
  annotations live in this browser's `localStorage` (`feedback-annotations-<path>`) and nothing
  leaves the machine. The bundle is vendored in `devtools/agentation/` — no CDN, works offline.
- **`devtools/agentation/` is outside every source set**, so `sboxmarket-1.0.0.jar` contains
  zero Agentation bytes. It is served off disk by a filter that only exists when the variable
  is set; edit the bundle and reload, no rebuild.
- **Toolbar buttons**, left to right: pause animations (`p`), layout mode (`l`), hide markers
  (`h`), copy (`c`), send (`s`), clear all (`x`), settings, and the feedback toggle. Settings
  has an **Output Detail** cycle — raise it past *Standard* to include computed styles.
- **Licence:** PolyForm Shield 1.0.0 — free to use, copy and modify, except to build something
  that competes with Agentation. Internal dev tooling is squarely inside that. Copies of both
  licences sit next to the bundle.
- **Proof it cannot reach production:** `src/test/groovy/com/sboxmarket/config/AgentationIsAbsentByDefaultSpec.groovy`
  and `AgentationDevGateSpec.groovy`. The gate itself is `config/AgentationDevGate.groovy`.

</details>
