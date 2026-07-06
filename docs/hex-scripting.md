# HexDroid's scripting

A `.hex` script is a small, sandboxed program that reacts to chat events, talks to
the network, draws UI, and calls HTTP/JSON. The interpreter (parser + backend +
view renderer) ships inside the APK; **features ship as `.hex` scripts** that the
engine loads. The bundled examples are `poker.hex` and `translate.hex`.

Implementations: (`HexParser`, `HexScriptBackend`,
`HexView`, `ScriptSurface`, `ScriptEngine`, `HexDroidScriptHost`)

---

## 1. File anatomy

A script is a flat list of two things:

- **event handlers** `on <EVENT> {... }`
- **aliases** (named subroutines / slash-commands) `alias <name> {... }`

```
; comments run from a semicolon to end of line
on LOAD {
  set %greeted 0
}

alias hello {
  echo $chan Hello, $1!
}
```

Comments: everything from the first `;` on a line to the newline is stripped before
parsing. There are no block comments. Because stripping cuts at the first `;`, a
value containing a literal `;` cannot be written inline with a trailing comment on
the same line.

Whitespace is not significant beyond separating tokens; `{` / `}` group bodies and
may span lines. Statements are separated by newlines or by `|` on one line. eg:
(`poker_deal | return`).

---

## 2. Values, variables, arguments

Everything is fundamentally a **string**; numbers are strings that parse as numbers,
and there are two structured types (**list**, **map**) produced by functions.

### Globals `%name`
Persist for the lifetime of the engine (across events), shared by all scripts in the
one engine instance. Set with `set`, read as `%name`.

```
set %count 0
inc %count            ; +1
dec %count            ; -1
unset %count
```

> Because there is a single engine per app, `%` globals are **not** per-network or
> per-buffer, a value set while on one server is visible on another.

`inc`, `dec` and `unset` take the variable **name** (`inc %count`), not its value.

### Locals `set -l`
`set -l %name <value>` writes a variable that lives only for the current handler or
alias **call** (and any `foreach` item variable is local the same way). Locals shadow
globals of the same name while the call runs, then vanish. Use them for scratch values
inside an alias so two scripts don't collide on a shared `%global`.

### Fields `$field` (read-only context)
Populated per event/handler:

| field | meaning |
|---|---|
| `$network` | network id the event is on |
| `$buffer`, `$chan`, `$target` | the buffer/channel (all three alias the same value) |
| `$text` | the message text (TEXT/ACTION/INPUT) |
| `$nick` | sender's nick (when present) |
| `$me` | your current nick |
| `$isme` | `"true"` / `"false"` did this line originate from you |

Signals carry their own extra fields (e.g. HTTP callbacks add `$httpok`,
`$httpstatus`, `$httpbody` - see §10).

### Positional arguments `$0 $1 $2...`
Handlers and aliases receive arguments:

- `$0` the **number** of arguments (not the text). Use `$1-` for the whole argument string.
- `$N` the Nth argument (1-based)
- `$N-` argument N through the end, space-joined (e.g. `$2-`)
- `$N-M` arguments N through M, space-joined

For `alias`, the args are what followed the command. For `SIGNAL:` handlers, the
args are whatever was passed to `signal`/`timer`/`http.*` as trailing context.

### Escaping
Write a literal dollar with `$$` and a literal percent with `%%`. A **lone** sigil that
is not followed by a name character is also taken literally, so `50%` and `cost $5 today`
survive as written, and `%` means modulo inside `$calc(...)` rather than being read as an
empty variable. (A sigil is only an expression when a letter, digit or `_` follows it.)

---

## 3. Events

Register with `on <EVENT> {... }`. Event names are case-insensitive.

| event | fires when | notes |
|---|---|---|
| `LOAD` | after every (re)load of the scripts | set up globals, register sidebar launchers here |
| `TEXT` | an **incoming** (or your own) chat line arrives | can transform or suppress it (see below) |
| `ACTION` | like TEXT but for `/me` actions | |
| `INPUT` | you send a line (before it goes out) | can transform/suppress your outgoing text |
| `SIGNAL:<name>` | a `signal`, `timer`, or HTTP callback fires `<name>` | your own async plumbing |

Optional glob filter on TEXT/ACTION: `on TEXT:*help*` only runs when the text
matches the glob.

**Transforming / suppressing text.** In `TEXT`/`ACTION`/`INPUT` handlers:
- `rewrite <new text>` replaces the line that will be shown/sent.
- `halt` suppresses the line entirely (it is not shown/sent) and stops the handler.
- returning normally leaves the line unchanged.

`LOAD` runs on the app's main path at (re)load. TEXT/ACTION/INPUT run synchronously
when the line is processed. See §12 for threads.

---

## 4. Commands

One command per statement (verb first). Full set:

| command | purpose |
|---|---|
| `set %v <value>` | assign a global (value is `set`-trimmed); `set %v` with no value clears it to "" |
| `set -l %v <value>` | assign a **local** (current handler/alias call only) |
| `unset %v` | remove a variable |
| `inc %v` / `dec %v` | +1 / −1 numeric |
| `push %v <value>` | append to a list variable |
| `setat %map <key> <value>` | set a map/list entry |
| `rewrite <text>` | replace the current event's text (TEXT/ACTION/INPUT) |
| `echo <buffer> <text>` | print a local line into `<buffer>` (not sent to the server) |
| `msg <buffer> <text>` | send a PRIVMSG to `<buffer>` |
| `raw <line>` | send a raw IRC line |
| `signal <name> [args…]` | fire `SIGNAL:<name>` now |
| `timer <ms> <signal> [args…]` | fire `SIGNAL:<signal>` after `<ms>` |
| `sidebar add\|remove...` | manage a sidebar launcher (see §11) |
| `http.get <url> <signal> [ctx…]` | async GET, result to `SIGNAL:<signal>` (§10) |
| `http.post <url> <body> <signal> [ctx…]` | async POST (§10) |
| `view {... }` | mount an interactive UI (§9) |
| `foreach %x %collection {... }` | iterate a list/map (§7) |
| `while (<cond>) {... }` | loop while the condition holds (§7) |
| `if / elseif / else` | conditionals (§6) |
| `return` / `halt` / `break` / `continue` | control flow (§7) |
| `<aliasname> [args…]` | call another alias |

`toast`, `decorate` and `action` are accepted by the parser and dispatched as UI
intents, but the current host only implements `sidebar`; the others are no-ops
(reserved). Don't rely on them yet.

---

## 5. Functions

Call as `$name(arg, arg,...)`. Args are comma-separated and trimmed; they may
themselves be `$`/`%` expressions. Functions never mutate their inputs. Because args
are trimmed, a separator with surrounding spaces can't be passed literally - e.g.
`$join(%xs, , )` yields an empty separator, not `", "`.

**String:** `$len(s)` `$lower(s)` `$upper(s)` `$left(s,n)` `$right(s,n)`
`$substr(s,start[,len])` `$replace(s,find,to)` `$trim(s)` `$contains(s,sub)`→bool
`$indexof(s,sub)` `$repeat(s,n)` `$pad(s,n[,ch])` `$padleft(s,n[,ch])`
`$capitalize(s)` `$title(s)`

**Regex** (Java/Kotlin flavour; input capped at 20 000 chars):
`$re_match(s,pattern)`→bool `$re_find(s,pattern)` (first whole match)
`$re_group(s,pattern,n)` (capture group n; 0 = whole match)
`$re_replace(s,pattern,to)` (literal replacement - no `$1` backrefs, since `$` is the
script sigil). An invalid pattern makes `re_match`/`re_find`/`re_group` return empty and
`re_replace` return the input unchanged.

**Math:** `$calc(expr)` (arithmetic `+ - * / %` with parens, e.g. `$calc((%pot + %bet) % 2)`)
`$mod(a,b)` `$int(x)` (floor) `$round(x[,dp])` `$ceil(x)` `$abs(x)` `$pow(a,b)`
`$clamp(x,lo,hi)` `$rand(n)`→0..n−1 / `$rand(lo,hi)`>inclusive.
`$min(…)`/`$max(…)` take either several numeric args **or** a single list
`$max(3,9,2)` and `$max(%scores)` both work.

> **`/` is floating-point division.** `$calc(5 / 2)` is `2.5`, not `2`. For whole-number
> results (chip counts, indices, anything you store or compare as an integer) wrap it:
> `$int($calc(5 / 2))` → `2`, and take the remainder with `%`: `$calc(5 % 2)` → `1`. A
> stray fractional value that reaches a stored quantity will surprise you later.

**Collections:** `$list(…)` `$map(k,v,k,v,…)` `$get(coll,key)` `$len(coll)`
`$keys(map)` `$values(map)` `$has(coll,key)`→bool `$sort(coll)` `$reverse(coll)`
`$join(list[,sep])` `$split(s[,sep])` `$slice(coll,from,to)` `$concat(…)`
`$find(list,item)`→index `$count(coll,item)` `$sum(list)` `$range(lo,hi)`
`$pick(list)` (random element) `$shuffle(list)` `$tojson(value)`

> **Double-quoted function arguments are string literals.** The quotes delimit the value
> (one layer is stripped; `$`/`%` inside still expand), so a delimiter or separator that
> contains spaces or commas is written naturally: `$split(%s, " ")` splits on a space,
> `$split(%csv, ",")` on a comma, `$join(%l, ", ")` inserts a comma+space. The argument
> splitter ignores commas and parens inside quotes. Unquoted args behave as before:
> `$split(%s)` splits on spaces, `$split(%s, ~)` on a bare `~`.

**Misc:** `$setting(key)` (host settings; e.g. `applang`/`lang` → device language
code such as `en`) · `$json(body,path)` (§10) · `$urlencode(s)`

Calling an unknown `$name(...)` yields an empty string (the function set is closed) and
logs `hex: unknown function $name()` - check the log if a value comes back mysteriously
blank.

---

## 6. Conditionals & operators

```
if (%turn == 2) {... }
elseif (%turn > 2) {... }
else {... }
```

Comparison operators: `==` `!=` `<` `>` `<=` `>=`. If both sides parse as numbers
they compare numerically, otherwise lexically. Also:

- `isin` - `a isin b` is true when `b` contains the substring `a`
- `iswm` - wildcard match, e.g. `$text iswm hello*`

Word operators (`isin`, `iswm`) need surrounding spaces. Combine tests with `&&` / `||`
and negate a bare test with `!`. A **bare value** is truthy when it is non-empty and not
`"false"` or `"0"` - so `if (%flag) {... }` works.

Conditions are evaluated left to right: `||` groups are tried, each an `&&` chain of
comparisons, with no precedence between `&&`/`||` beyond that order. **Parenthesised
grouping is supported** and nests to any depth, and `!(...)` negates a whole group:

```
if ((%a == 1 || %b == 1) && %c >= 5) {... }
if (!(%started == 1 && %ishost == 0)) {... }
```

Function-call parens inside a comparison ($get, $len,...) are unaffected; a group is
recognised only when a `(...)` pair spans one whole `&&`/`||` term.

---

## 7. Control flow

- `return` leave the current handler/alias.
- `halt` suppress the current event's line (TEXT/ACTION/INPUT) and stop.
- `foreach %item %collection {... }` iterate a list's items, or a map's keys.
- `while (<cond>) {... }` loop while the condition (§6) holds; `break`/`continue` apply.
  There is no built-in iteration cap, so a `while` is bounded only by the step/time budget
  (§12) make sure the condition can become false.
- `break` / `continue` inside a `foreach` or `while`.
- `timer <ms> <signal> [ctx...]` schedule work; the signal handler picks it up.
- `signal <name> [ctx..]` fire immediately.

```
foreach %fp %seats {
  if ($get(%folded,%fp) == 1) { continue }
  echo $chan seat: $get(%name,%fp)
}

set %i 0
while (%i < 3) {
  echo $chan tick %i
  set %i $calc(%i + 1)
}
```

---

## 8. Collections

Lists and maps are values you keep in globals:

```
set %seats $list()
push %seats alice
push %seats bob

set %stack $map()
setat %stack alice 1000
echo $chan $get(%stack, alice)     ; 1000
foreach %p %stack { echo $chan $p = $get(%stack,%p) }
```

`$tojson(%stack)` serialises to JSON (number-looking strings emit unquoted so they
round-trip); pair it with `$json()` to read structured data back.

---

## 9. The view DSL (`view {... }`)

`view {... }` mounts an interactive surface (rendered by `ScriptSurface`). It is
re-mountable - call `view` again to redraw with new state. When the user closes the
surface, the engine fires `SIGNAL:VIEW_CLOSED`, so bind cleanup there:

```
on SIGNAL:view_closed { my_reset }
```

### Elements
`column`, `row`, `stack` (z-stacked/overlapping), `ring` (radial), `surface`
(a single framed child), `text "…"`, `button "label" <actionId> [args…] [props]`,
`card <code> [red] [back]`, `image <url>`, `spacer`.

Containers take a `{... }` body of child elements. `foreach %x %coll {... }` works
**inside** a view body to lay out data-driven children (N seats, a list,...).

### Props (after an element, space-separated)
Flags: `bold`, `fill` (fill width), `wrap` (a `row` flows onto extra lines instead
of overflowing), `circle`, `ellipse` (an oval shape on a `surface` - a poker felt,
a token). Card flags: `red` (red suit), `back` (face-down). Image scale flags:
`crop` (default, fills and clips), `fit` (letterbox), `stretch` (distort to fill).

Value props: `width <dp>`, `height <dp>`, `size <dp>`, `radius <dp>`,
`pad <dp>`, `gap <dp>`, `weight <f>`, `color <c>`, `bg <c>`, `textsize <dp>`,
`offsetx <dp>`, `offsety <dp>`, `align <center|start|end|top|bottom>`,
`border <color> [width]`, `gradient...`, `elevation`/`elev`,
`bgimage <url>` (a `surface` background image, painted behind its child - layer a
translucent `gradient` over it as a readability tint).

A remote image loads from its URL with no bundled asset, so rich table art costs no
APK size. Compose a scene by layering: an outer `surface` with a `bgimage`, an
`ellipse` felt `surface` on top, `image <url> circle` avatars, and `card`s.

```
view {
  column gap 8 pad 12 {
    text "Table" bold
    row gap 6 wrap {
      foreach %fp %seats { card $get(%hole,%fp) width 30 }
    }
    row gap 8 {
      button "Fold" poker_fold
      button "Call" poker_call
    }
  }
}
```

### Buttons >→< actions
A button's first token is an **action id**; remaining non-prop tokens are args.
Tapping it runs an alias of that name if one exists, otherwise fires
`SIGNAL:<ACTIONID>`. So `button "Deal" poker_start` calls `alias poker_start` (or
fires `SIGNAL:POKER_START`).

---

## 10. HTTP + JSON

```
http.get  <url> <signal> [ctx…]
http.post <url> <body> <signal> [ctx…]
```

Both are **asynchronous**: they fire the request and return immediately; when it
completes, `SIGNAL:<signal>` runs with these extra fields plus your `ctx` as
`$1 $2...`:

| field | meaning |
|---|---|
| `$httpok` | `"true"` if status 200-299 |
| `$httpstatus` | numeric HTTP status |
| `$httpbody` | response body |

The POST body's `Content-Type` is inferred: a body that looks like
`key=value&key=value` (has `=`, no spaces) is sent form-urlencoded; otherwise it's
sent as-is. `$urlencode()` your values so the body stays space-free.

The callback (and any `signal`/`timer` you fire from a handler) runs on the **same
network and buffer** the originating event came from, even if you have since switched
to another network. So `echo`, `msg`, and `raw` inside the callback go back to where
the message was, not to whatever window is active when the reply lands. Thread the
buffer through `ctx` (as `translate.hex` does with `$buffer`) so you know where to echo.

Read JSON out of the body with `$json(body, path)`. The path is dot-separated and
supports **object keys and array indices**:

```
$json($httpbody, translatedText)                 ; {"translatedText": "..."}
$json($httpbody, detectedLanguage.language)       ; nested object
$json($httpbody, 0.2)                             ; arrays: element [0] then [2]
```

Because the request is async and its handler can `echo` into the passed-through
buffer, the classic pattern is to thread the target buffer + original text as ctx:

```
on TEXT {
  if ($isme == true) { return }
  http.post %ep q=$urlencode($text)&target=en tr_done $buffer $text
}
on SIGNAL:tr_done {
  if ($httpok == true) { echo $1 ↳ $json($httpbody, translatedText) }
}
```

Network access is subject to the host's policy; a blocked or failed request returns
`$httpok == false` (don't forget an `else` - a silent success-only handler is why a
failing endpoint looks like "nothing happened").

---

## 11. Sidebar launchers

Scripts contribute entries to the app's script launcher list:

```
on LOAD { sidebar add poker poker_open Poker Night }
```

`sidebar add <id> <command> <label words…>` registers a launcher that runs
`<command>` (an alias, or a `SIGNAL:`) when tapped; `sidebar remove <id>` drops it.

---

## 12. Threads & lifecycle

- `LOAD` and user-driven alias/button actions run on the app's main path.
- `TEXT`/`ACTION`/`INPUT` run **synchronously** while the line is processed;
  return quickly. Do slow work by firing an `http.*`/`timer` and finishing in the
  signal handler.
- `timer` callbacks and `http.*` result callbacks run on a single background worker
  thread. Because there is one shared variable space, avoid assuming a bot loop and
  a user action never interleave; keep state transitions in one place.
- Signals fire the matching `SIGNAL:` handler wherever they were raised.

A script is dropped and reloaded (its handlers cleared and re-registered, `LOAD`
re-fired) on reload; globals do not survive a reload.

---

## 13. Sandbox notes

- No filesystem, no arbitrary process access, only the commands/functions above.
- Networking goes through the host HTTP with a policy check; there is no raw socket.
- A script only affects buffers/networks via `echo`/`msg`/`raw`, and UI via
  `view`/`sidebar`.
- Bundled scripts ship **disabled**; the user opts in from the Scripts screen.
  Editing a bundled script in-app preserves your edits across app updates; unedited
  bundled scripts are refreshed to the shipped version on update.

---

## 14. `age.*` encrypted transport for scripted games **(HexDroid)**

These are part of the app, not core language, used by games eg: `poker.hex` to run over the `+AGE`
encrypted channel/among peers (and to drive practice bots locally):

Commands: `age.join <chan>`, `age.host <chan>`, `age.send <chan> <tokens…>`
(send to peers, fail-closed until keyed), `age.local <from> <tokens…>` (inject an
event as another local seat - practice bots), `age.seal <fp> <c1> <c2>`.
Functions: `$age.me` (your fingerprint), `$age.rand(n)`, `$age.sha(s)`.

Incoming peer messages surface as `SIGNAL:AGE_MSG` with the sender's fingerprint in
`$from` and the payload tokens in `$1 $2...`. Sealed hole cards arrive as
`SIGNAL:AGE_DEAL` with `$from` and the dealt tokens in `$data`. See `poker.hex` for a
full worked example.

---

## 15. Example

```
; shout.hex 
; echo a loud copy of anything containing "!!!"
on LOAD { sidebar add shout shout_help Shout help }
alias shout_help { echo $chan shout.hex: I echo lines containing !!! in caps }

on TEXT {
  if ($isme == true) { return }
  if ($text iswm *!!!*) { echo $chan 📣 $upper($text) }
}
```
