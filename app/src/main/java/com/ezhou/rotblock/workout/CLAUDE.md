# Knowing a workout is on, without reading the phone's notifications

`workout/WorkoutListener.kt` is a `NotificationListenerService`, and it is the broadest grant this
app holds. Android offers no way to scope one to a single package, so **the narrowing is in code and
has to stay checkable**:

- Both callbacks return on their first line unless the package matches the configured one. `extras`
  is never touched, for any package — including the workout app's.
- The only values that ever leave a `StatusBarNotification` are one flag bit and `when`. No title, no
  text, no channel, no id.
- None of it is persisted, logged, or written into a diagnostic capture. The capture's
  `WorkoutSnapshot` holds rotblock's own derived state — a package the user chose, an enum, two
  timestamps — and nothing else may be added to it.
- There is no `INTERNET` permission and there must never be one, so egress is impossible rather than
  merely avoided.
- A unit test asserts the listener ignores a non-configured package, so the first rule cannot rot
  silently.

The signal itself is measured, not assumed. Strong (`io.strongapp.strong`) posts exactly one
notification for the life of a workout — `ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE`, so it exists
for the whole session and cannot be swiped away — and sets `when` to the real workout start, which is
what lets the cap run from when the workout began rather than from when we noticed.

The two halves are deliberately different kinds of evidence. The ongoing flag is **structural**, part
of the notification's own contract. `when` is **heuristic**: nothing obliges Strong to keep setting
it, so `workoutStartFrom` falls back to first sighting for anything that is not a sane past
timestamp. Keying on the *title* would have been the third kind — somebody else's localized string,
one redesign from breaking — which is why nothing here reads it. Same rule as `FeedRegistry`: a miss
degrades to "no workout", never to a false one.

The workout app is a setting, not a constant. Nothing above is specific to Strong beyond the default.
