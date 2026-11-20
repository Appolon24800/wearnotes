# Wear OS visual system

The Wear app uses the round screen as a constraint, not as a phone layout
with its corners removed.

## Tokens

```text
Ink       #F5EEE8
Cocoa     #17110E
Cocoa 2   #251C17
Coral     #FFB59A
Sea glass #76D0C9
Violet    #D8BFFF
Gold      #FFD36E
```

The cocoa surface gives the watch a quiet background. Coral identifies the
one primary action. Sea glass identifies sync state. Note colors appear only
as a narrow left rail so color never carries meaning by itself.

## Round layout

The large-round emulator is 454 by 454 pixels. The main list uses a 32 dp
horizontal inset and keeps its header centered. The refresh action stays on
the right side of that header. The primary action is centered near the bottom
with a 140 by 40 dp footprint.

Important content never reaches the outside ring. Note rows use one preview
line on the recent screen, which keeps the bottom action from covering a
second line. Long text belongs on the detail screen.

## Interaction

- Tap a note to read it.
- Swipe right to return.
- Tap Refresh to request the current server list.
- Tap New note to write locally and upload it.
- Tap Archive on the detail screen to remove a note from Recent.

The phone is not required for these actions. The watch uses the server over
its own network connection and retains the last successful list in memory for
the current session.
