# Capturing the state a bug was in

`diag/` exists because of a circularity: every failure here presents as "the app is open and nothing
happens", and the one way to look at it — opening rotblock — changes the foreground window and
clears the state worth looking at. Setup → **Right now** answers what enforcement believes, but only
once you already suspect something and only while you are standing in front of it.

So a second Quick Settings tile writes the whole state to a file. It deliberately does **not**
collapse the shade or start an activity: the app underneath the tap is untouched, which is the
entire reason this is a tile rather than a launcher icon. A capture holds what
`EnforcementStatus` believed plus its whole trail (which is why the trail is 300 entries and not 40
— a capture is taken *after* something went wrong, so the buffer must already hold the minutes
before you noticed), the service internals no snapshot carried, the live `PolicyEngine` decision for
the current target with its sessions, the requirements, the phone's own answers, every rule, and the
tail of the audit log.

The node tree is the valuable part, and it arrives late by necessity: while the shade is open
`rootInActiveWindow` belongs to systemui, so the request *arms* a dump and the service takes it once
the shade closes. A window only has to not be ours and not be the shade — agreeing with
`currentPackage` is recorded rather than required, because a service that never worked out what was
in front is precisely one of the failures being investigated and the case where a tree is worth
most. Refusing there would have made the tool blindest exactly where it is needed.

That rule is `canDumpWindow`, and it lives beside `classifyInspection` rather than inside the
service because it was once written down here and only half implemented in code: the first attempt
relaxed the `currentPackage == null` case and left the requirement standing for every case where the
service *did* know what was in front, which is the common one. A capture taken on device came back
with no tree at all as a result. Being a pure function with tests is what stops the documented
contract and the running code from drifting apart again. "Recorded rather than required" is the other
half and is not optional: `dumpStatus` names both packages in the dump's status, so a reader can tell
the window the service expected from some other window recorded because refusing would have given
nothing. Recording a disagreement silently, as if it agreed, would be its own misleading capture.

**A capture in flight has to survive the service going away**, which is the one moment it is worth
most and the one moment it used to be lost. The request gathers everything immediately and then waits
up to fifteen seconds for a readable window; `teardown` cancels every pending handler callback, so a
capture taken in that window wrote no file, logged no error, and left no trace — a tile tap that did
nothing at all. So the pending capture is held in a field from the moment the request is gathered
(before the suspending facts read, which a teardown can also land in the middle of) and flushed by
`teardown` before it clears the handler. That write is **synchronous**, on the teardown thread, for
the same reason the outage timestamp beside it uses `commit()` rather than `apply()`: teardown is
usually followed by process death, so a write dispatched to `Dispatchers.IO` there is exactly the
write that never finishes.

Alongside the raw
tree it records rotblock's *own reading* of it — what the address bar returned, what the links voted,
what the signature said, what `classifyInspection` concluded — because a tree that looks fine next
to a wrong conclusion is a completely different bug from a tree with nothing in it, and only having
both tells them apart.

Captures live in `getExternalFilesDir("diagnostics")`: still unreadable by other apps, and reachable
with `adb pull /sdcard/Android/data/com.ezhou.rotblock/files/diagnostics/`, which is how one
actually gets in front of anyone debugging it. Newest 20 are kept. **A capture contains the page
URLs and on-screen text that led to a decision, which makes it the most sensitive thing this app
holds** — it never leaves the phone on its own, there is no share path, and Setup says so plainly
next to the button.

**Package visibility is a trap for future lookups.** `QUERY_ALL_PACKAGES` is gone (Play refuses it
when a scoped declaration would do), so the manifest's `<queries>` block is the entire list of what
this app can see: launchable apps, and things that handle http(s). Any new `PackageManager` call
outside that returns empty or throws `NameNotFoundException` rather than failing loudly — including
`startActivity` with `setPackage()`, which is why the escape intent's scheme is declared there.
