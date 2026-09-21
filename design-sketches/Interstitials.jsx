(() => {
const DS = window.EthanDesignSystem_8dc3d3;
const { Button, Icon, Input, Switch } = DS;
const { useState, useEffect, useRef } = React;

const STATS = {
  target: { label: "reddit.com", today: 102 },
  all: { label: "all blocked", today: 198 },
};

function buildMonth() {
  const app = [];
  const all = [];
  for (let i = 0; i < 29; i++) {
    const weekend = i % 7 === 5 || i % 7 === 6;
    const w = ((i * 13) % 7) / 20;
    const a = Math.max(0.2, (weekend ? 1.1 : 0.65) + w);
    app.push(Math.round(a * 10) / 10);
    all.push(Math.round((a + 0.7 + (weekend ? 0.5 : 0.3)) * 10) / 10);
  }
  app.push(1.7);
  all.push(3.3);
  const avg30 = app.reduce((s, v) => s + v, 0) / 30;
  return { appH: app, allH: all, avg30 };
}
const MONTH = buildMonth();

const EM_MINS = Array.from({ length: 30 }, () => 0);
[3, 7, 12, 16, 19, 23, 26, 29].forEach((i, n) => { EM_MINS[i] = n === 4 ? 45 : 15; });
const EM_AVG = EM_MINS.reduce((s, v) => s + v, 0) / 30;

const SCOPES = [
  { id: "app", title: "reddit.com", menu: "Just reddit.com" },
  { id: "group", title: "brainrot", menu: "All of brainrot" },
  { id: "all", title: "everything", menu: "Everything" },
];
const DURATIONS = ["5 min", "15 min", "30 min", "60 min"];

const SCREENS = [
  { id: "friction", group: "Friction", label: "Countdown" },
  { id: "block-quota", group: "Blocked", label: "Time used up" },
  { id: "block-hard", group: "Blocked", label: "Hard block" },
  { id: "block-workout", group: "Blocked", label: "Workout" },
  { id: "pause-choose", group: "Pause", label: "Ask" },
  { id: "pause-cool", group: "Pause", label: "Wait" },
  { id: "pause-emergency", group: "Pause", label: "Emergency" },
];

function fmt(min) {
  const n = Math.abs(min);
  const h = Math.floor(n / 60);
  const m = Math.round(n % 60);
  if (h > 0 && m > 0) return `${h}h ${m}m`;
  if (h > 0) return `${h}h`;
  return `${m}m`;
}

function StatusBar() {
  return (
    <div style={{
      display: "flex", alignItems: "center", justifyContent: "space-between",
      height: 44, padding: "0 22px 0 26px", flex: "none",
      fontFamily: "var(--font-mono)", fontSize: 13, fontWeight: 500,
      color: "var(--text-heading)",
    }}>
      <span>9:41</span>
      <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
        <Icon name="signal" size={14} />
        <Icon name="wifi" size={14} />
        <Icon name="battery-full" size={16} />
      </span>
    </div>
  );
}

function Phone({ children, darkChrome }) {
  return (
    <div style={{
      position: "relative", display: "flex", flexDirection: "column",
      width: 390, height: 844, overflow: "hidden",
      background: darkChrome ? "#101014" : "var(--bg-app)",
      borderRadius: 34, border: "1px solid var(--border-default)",
      boxShadow: "var(--shadow-3)", color: darkChrome ? "#ECEEF1" : "inherit",
    }}>
      {children}
    </div>
  );
}

function Cluster({ children }) {
  return (
    <div style={{
      flex: 1, minHeight: 0, display: "flex", flexDirection: "column",
      justifyContent: "center", padding: "12px 24px 28px", textAlign: "center",
    }}>
      {children}
    </div>
  );
}

function TextLink({ children, onClick, danger = false }) {
  return (
    <button type="button" onClick={onClick} style={{
      display: "flex", alignItems: "center", justifyContent: "center",
      width: "100%", minHeight: 40, padding: "8px 8px",
      background: "none", border: 0, cursor: "pointer",
      fontSize: "var(--fs-body)",
      color: danger ? "var(--text-danger)" : "var(--text-muted)",
      textDecoration: "underline",
      textDecorationThickness: 1,
      textUnderlineOffset: 3,
      textDecorationColor: danger
        ? "color-mix(in oklch, var(--hazard-500) 45%, transparent)"
        : "color-mix(in oklch, var(--underline-ink) 40%, transparent)",
    }}>{children}</button>
  );
}

function Primary({ children, onClick, disabled, danger }) {
  return (
    <Button
      variant={danger ? "danger" : "primary"}
      block
      disabled={disabled}
      onClick={onClick}
      style={{ height: 44, fontSize: "var(--fs-body)", borderRadius: "var(--r-control)" }}
    >{children}</Button>
  );
}

function Actions({ children }) {
  return <div style={{ marginTop: 28 }}>{children}</div>;
}

function Compare({ minutes, vs }) {
  const more = minutes > 0;
  const same = !minutes;
  return (
    <div style={{
      fontFamily: "var(--font-mono)",
      color: same ? "var(--text-faint)" : more ? "var(--more)" : "var(--less)",
    }}>
      <div style={{
        fontSize: "var(--fs-title-2)",
        lineHeight: "var(--lh-title-2)",
        letterSpacing: "var(--tr-title-2)",
        fontWeight: 600,
      }}>
        {same ? "same" : `${fmt(minutes)} ${more ? "more" : "less"}`}
      </div>
      <div style={{
        marginTop: 2, fontSize: "var(--fs-caption)", lineHeight: 1.35,
        fontWeight: 400, color: "inherit",
      }}>
        than {vs}
      </div>
    </div>
  );
}

function HourClock({ frac, size = 22 }) {
  const r = size / 2 - 1.6;
  const cx = size / 2;
  const cy = size / 2;
  const t = Math.max(0, Math.min(1, frac));
  const a = t * 2 * Math.PI;
  const x = cx + r * Math.sin(a);
  const y = cy - r * Math.cos(a);
  const large = t > 0.5 ? 1 : 0;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
      <circle cx={cx} cy={cy} r={r} fill="color-mix(in oklch, var(--more) 16%, var(--paper-0))"
        stroke="var(--more)" strokeWidth="1.2" />
      {t >= 0.98
        ? <circle cx={cx} cy={cy} r={r - 0.4} fill="var(--more)" />
        : t > 0.04 && (
          <path
            d={`M ${cx} ${cy} L ${cx} ${cy - r} A ${r} ${r} 0 ${large} 1 ${x} ${y} Z`}
            fill="var(--more)"
          />
        )}
      <circle cx={cx} cy={cy - r + 0.2} r="1.15" fill="var(--more)" />
    </svg>
  );
}

function ClockRow({ minutes, label }) {
  const hours = minutes / 60;
  const full = Math.floor(hours);
  const part = hours - full;
  const fracs = [];
  for (let i = 0; i < full; i++) fracs.push(1);
  if (part > 0.08) fracs.push(part);
  if (fracs.length === 0 && minutes > 0) fracs.push(0.2);
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 5 }}>
      {label && (
        <div style={{
          fontFamily: "var(--font-mono)", fontSize: "var(--fs-micro)",
          letterSpacing: "var(--tr-micro)", color: "var(--text-faint)",
        }}>{label} · {fmt(minutes)} today</div>
      )}
      <div style={{ display: "flex", flexWrap: "wrap", justifyContent: "center", gap: 5 }}
        role="img" aria-label={`${fmt(minutes)} as hours`}>
        {fracs.map((f, i) => <HourClock key={i} frac={f} />)}
      </div>
    </div>
  );
}

function yScale(maxValue, unit) {
  if (unit === "m") {
    const cramped = maxValue > 90;
    if (!cramped) {
      const step = 15;
      const top = Math.max(step, Math.ceil(maxValue / step) * step);
      const ticks = [];
      for (let v = step; v <= top; v += step) ticks.push({ v, label: `${v}m` });
      return { top, ticks };
    }
    const top = Math.max(60, Math.ceil(maxValue / 60) * 60);
    const ticks = [];
    for (let v = 60; v <= top; v += 60) ticks.push({ v, label: `${v / 60}h` });
    return { top, ticks };
  }
  const top = Math.max(1, Math.ceil(maxValue));
  const ticks = [];
  for (let v = 1; v <= top; v++) ticks.push({ v, label: `${v}h` });
  return { top, ticks };
}

function DayChart({ values, overlay, unit, label }) {
  const w = 318, h = 108, padB = 16, padT = 4, padL = 26, padR = 4;
  const innerH = h - padB - padT;
  const innerW = w - padL - padR;
  const rawMax = Math.max(...values, ...(overlay || []), 0.01);
  const { top, ticks } = yScale(rawMax, unit);
  const n = values.length;
  const slot = innerW / n;
  const barW = Math.max(2.5, slot * 0.42);
  const y = (v) => padT + innerH * (1 - v / top);
  const ht = (v) => innerH * (v / top);
  return (
    <svg width={w} height={h} style={{ display: "block", margin: "0 auto" }}
      role="img" aria-label={label}>
      {values.map((v, i) => {
        const x = padL + i * slot + slot / 2;
        const today = i === n - 1;
        const ov = overlay ? overlay[i] : 0;
        return (
          <g key={i}>
            {overlay && (
              <rect x={x - barW * 0.7} y={y(ov)} width={barW * 1.4} height={ht(ov)}
                fill="var(--ink-100)" rx="0.5" />
            )}
            <rect x={x - barW * 0.38} y={y(v)} width={barW} height={Math.max(ht(v), v > 0 ? 1.5 : 0)}
              fill={today ? "var(--ink-900)" : "var(--ink-700)"} rx="0.5" />
          </g>
        );
      })}
      {ticks.map((tk) => (
        <g key={tk.label}>
          <line x1={padL} x2={w - padR} y1={y(tk.v)} y2={y(tk.v)}
            stroke="var(--ink-500)" strokeWidth="1" />
          <line x1={padL} x2={w - padR} y1={y(tk.v)} y2={y(tk.v)}
            stroke="var(--paper-0)" strokeWidth="1" strokeOpacity="0.7" />
          <text x={padL - 4} y={y(tk.v) + 3} textAnchor="end"
            fill="var(--text-faint)" fontFamily="var(--font-mono)" fontSize="9">{tk.label}</text>
        </g>
      ))}
      <text x={padL} y={h - 2} fill="var(--text-faint)" fontFamily="var(--font-mono)" fontSize="9">30d</text>
      <text x={w - padR} y={h - 2} textAnchor="end" fill="var(--text-heading)"
        fontFamily="var(--font-mono)" fontSize="9">today</text>
    </svg>
  );
}

function UsageViz({ dense = false }) {
  const usualMin = Math.round(MONTH.avg30 * 60);
  const delta = STATS.target.today - usualMin;
  return (
    <div>
      <ClockRow minutes={STATS.target.today} label={STATS.target.label} />
      <div style={{ marginTop: 6 }}>
        <Compare minutes={delta} vs="your 30-day average" />
      </div>
      {!dense && (
        <>
          <div style={{ marginTop: 12 }}>
            <ClockRow minutes={STATS.all.today} label={STATS.all.label} />
          </div>
          <div style={{ marginTop: 14 }}>
            <DayChart
              values={MONTH.appH}
              overlay={MONTH.allH}
              unit="h"
              label="Hours per day for 30 days. Pale bar is everything blocked."
            />
          </div>
        </>
      )}
    </div>
  );
}

function Ring({ left, total, size = 128 }) {
  const p = total <= 0 ? 1 : 1 - left / total;
  const r = size / 2 - 6;
  const c = 2 * Math.PI * r;
  return (
    <div style={{ position: "relative", width: size, height: size, margin: "0 auto" }}>
      <svg width={size} height={size} style={{ transform: "rotate(-90deg)" }}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--ink-100)" strokeWidth="5.5" />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--ink-800)" strokeWidth="5.5"
          strokeDasharray={c} strokeDashoffset={c * (1 - p)} strokeLinecap="round" />
      </svg>
      <div style={{
        position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center",
        fontFamily: "var(--font-mono)", fontSize: 40, fontWeight: 400,
        letterSpacing: "-0.04em", color: "var(--text-heading)",
      }}>{left > 0 ? left : "→"}</div>
    </div>
  );
}

function Pills({ options, value, onChange }) {
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 6, justifyContent: "center" }}>
      {options.map((opt) => {
        const on = opt === value;
        return (
          <button key={opt} type="button" onClick={() => onChange(opt)} style={{
            height: 36, padding: "0 12px", borderRadius: "var(--r-full)", cursor: "pointer",
            border: `1px solid ${on ? "var(--ink-700)" : "var(--border-default)"}`,
            background: on ? "var(--ink-800)" : "transparent",
            color: on ? "var(--text-inverse)" : "var(--text-muted)",
            fontSize: "var(--fs-caption)", fontWeight: 500, whiteSpace: "nowrap",
          }}>{opt}</button>
        );
      })}
    </div>
  );
}

function ScopeTitle({ value, onChange }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [open]);
  const current = SCOPES.find((s) => s.id === value) || SCOPES[0];
  return (
    <h1 style={{
      fontSize: "var(--fs-title-1)", lineHeight: 1.25,
      letterSpacing: "var(--tr-title-1)", fontWeight: 600,
    }}>
      Pause blocking for{" "}
      <span ref={ref} style={{ position: "relative", display: "inline-block" }}>
        <button type="button" onClick={() => setOpen((o) => !o)} style={{
          background: "none", border: 0, borderBottom: "1px dashed var(--ink-400)",
          font: "inherit", fontFamily: "var(--font-heading)", fontWeight: 600,
          color: "var(--text-heading)", cursor: "pointer", padding: "0 1px 1px",
        }}>
          {current.title}
          <span style={{ color: "var(--text-faint)", fontSize: 11, marginLeft: 3, fontFamily: "var(--font-ui)" }}>▼</span>
        </button>
        {open && (
          <div style={{
            position: "absolute", left: "50%", top: "calc(100% + 6px)", transform: "translateX(-50%)",
            minWidth: 168, background: "var(--surface-raised)",
            border: "1px solid var(--border-default)", borderRadius: "var(--r-md)",
            boxShadow: "var(--shadow-2)", zIndex: 5, padding: 4, textAlign: "left",
          }}>
            {SCOPES.map((s) => (
              <button key={s.id} type="button" onClick={() => { onChange(s.id); setOpen(false); }} style={{
                display: "block", width: "100%", textAlign: "left",
                padding: "8px 10px", border: 0, borderRadius: "var(--r-sm)", cursor: "pointer",
                background: s.id === value ? "var(--ink-wash)" : "transparent",
                color: "var(--text-heading)", fontSize: "var(--fs-body-sm)",
              }}>{s.menu}</button>
            ))}
          </div>
        )}
      </span>
    </h1>
  );
}

function HoldToConfirm({ label, onFire }) {
  const [holding, setHolding] = useState(false);
  const [frac, setFrac] = useState(0);
  const start = useRef(0);
  useEffect(() => {
    if (!holding) { setFrac(0); return undefined; }
    start.current = performance.now();
    let raf;
    const tick = (now) => {
      const f = Math.min(1, (now - start.current) / 3000);
      setFrac(f);
      if (f >= 1) { setHolding(false); onFire(); return; }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [holding, onFire]);
  const down = () => setHolding(true);
  const up = () => setHolding(false);
  return (
    <button
      type="button"
      onMouseDown={down} onMouseUp={up} onMouseLeave={up}
      onTouchStart={(e) => { e.preventDefault(); down(); }}
      onTouchEnd={up}
      style={{
        position: "relative", display: "flex", alignItems: "center", justifyContent: "center",
        width: "100%", height: 44, overflow: "hidden", cursor: "pointer",
        background: "var(--danger)", color: "var(--on-danger)",
        border: "1px solid var(--danger)", borderRadius: "var(--r-control)",
        fontSize: "var(--fs-body)", fontWeight: 500, userSelect: "none",
      }}
    >
      <span style={{
        position: "absolute", left: 0, top: 0, bottom: 0, width: (frac * 100) + "%",
        background: "var(--danger-hover)",
      }} />
      <span style={{ position: "relative" }}>{label}</span>
    </button>
  );
}

function useCountdown(seconds, running) {
  const [left, setLeft] = useState(seconds);
  useEffect(() => {
    setLeft(seconds);
    if (!running) return undefined;
    const id = setInterval(() => {
      setLeft((n) => {
        if (n <= 1) { clearInterval(id); return 0; }
        return n - 1;
      });
    }, 1000);
    return () => clearInterval(id);
  }, [seconds, running]);
  return left;
}

function useWhenZero(left, onZero) {
  const fired = useRef(false);
  useEffect(() => {
    if (left === 0 && !fired.current) {
      fired.current = true;
      onZero();
    }
  }, [left, onZero]);
}

function EmergencyViz() {
  const today = EM_MINS[EM_MINS.length - 1];
  const delta = today - EM_AVG;
  return (
    <div>
      <DayChart
        values={EM_MINS}
        unit="m"
        label="Minutes of emergency skip per day for 30 days."
      />
      <div style={{ marginTop: 10 }}>
        <Compare minutes={Math.round(delta)} vs="your 30-day average" />
      </div>
    </div>
  );
}

function ProposedFriction({ history, onLeave, onContinue }) {
  const total = 5;
  const left = useCountdown(total, true);
  return (
    <Cluster>
      <Ring left={left} total={total} />
      <h1 style={{
        marginTop: 22, fontSize: "var(--fs-title-1)", lineHeight: "var(--lh-title-1)",
        letterSpacing: "var(--tr-title-1)",
      }}>reddit.com</h1>
      <p style={{ marginTop: 6, fontSize: "var(--fs-body)", color: "var(--text-muted)" }}>
        Was there something you actually wanted here?
      </p>
      {history && <div style={{ marginTop: 16 }}><ClockRow minutes={STATS.target.today} /></div>}
      <Actions>
        <Primary disabled={left > 0} onClick={onContinue}>{left > 0 ? "Wait…" : "Go ahead"}</Primary>
        <TextLink onClick={onLeave}>Not really — take me back</TextLink>
      </Actions>
    </Cluster>
  );
}

function ProposedBlock({ kind, history, onPause, onLeave, onClose }) {
  const website = kind === "quota";
  const reason = kind === "quota" ? "Time's up" : kind === "workout" ? "Workout" : "Blocked";
  const lift = kind === "quota" ? "in 47m" : kind === "hard" ? "in 2h 14m" : null;
  const target = kind === "hard" ? "Instagram" : "reddit.com";
  return (
    <Cluster>
      <h1 className="penrule" style={{
        fontSize: "var(--fs-display-2)", lineHeight: "var(--lh-display-2)",
        letterSpacing: "var(--tr-display-2)",
      }}>{reason}</h1>
      <div style={{
        marginTop: 8, fontSize: "var(--fs-title-3)", color: "var(--text-body)",
      }}>{target}</div>
      {lift && (
        <div style={{
          marginTop: 4, fontFamily: "var(--font-mono)", fontSize: "var(--fs-caption)",
          color: "var(--text-faint)",
        }}>{lift}</div>
      )}
      {history && <div style={{ marginTop: 18 }}><UsageViz /></div>}
      <Actions>
        <Primary onClick={website ? onLeave : onClose}>{website ? "Leave this site" : "Close this app"}</Primary>
        {website && <TextLink onClick={onClose}>Close this app</TextLink>}
        <TextLink onClick={onPause}>I actually need this</TextLink>
      </Actions>
    </Cluster>
  );
}

function ProposedPauseChoose({ history, budgetSpent, onStart, onCancel }) {
  const [scope, setScope] = useState(SCOPES[0].id);
  const [dur, setDur] = useState(DURATIONS[1]);
  return (
    <Cluster>
      <ScopeTitle value={scope} onChange={setScope} />
      <div style={{ marginTop: 18 }}>
        <Pills options={DURATIONS} value={dur} onChange={setDur} />
      </div>
      <div style={{
        marginTop: 10, fontFamily: "var(--font-mono)", fontSize: "var(--fs-micro)",
        letterSpacing: "var(--tr-micro)",
        color: budgetSpent ? "var(--more)" : "var(--text-faint)",
      }}>
        {budgetSpent ? "4/4 pauses · budget spent" : "2/4 pauses · 25/60 min"}
      </div>
      {history && <div style={{ marginTop: 18 }}><UsageViz dense /></div>}
      <Actions>
        <Primary onClick={onStart}>
          {budgetSpent ? "Emergency skip only" : "Wait, then pause"}
        </Primary>
        <TextLink onClick={onCancel}>Never mind</TextLink>
      </Actions>
    </Cluster>
  );
}

function ProposedPauseCool({ onAbandon, onEmergency, onFinished }) {
  const total = 8;
  const left = useCountdown(total, true);
  useWhenZero(left, onFinished);
  return (
    <Cluster>
      <Ring left={left} total={total} size={140} />
      <p style={{ marginTop: 20, fontSize: "var(--fs-body)", color: "var(--text-body)" }}>
        15 min pause
      </p>
      <Actions>
        <TextLink onClick={onAbandon}>I changed my mind</TextLink>
        <TextLink danger onClick={onEmergency}>Skip the wait — I need this now</TextLink>
      </Actions>
    </Cluster>
  );
}

function ProposedEmergency({ budgetSpent, workout, onConfirm, onCancel }) {
  const [reason, setReason] = useState("");
  const filled = reason.trim().length > 0;
  return (
    <Cluster>
      <h1 style={{
        fontSize: "var(--fs-title-1)", lineHeight: "var(--lh-title-1)",
        letterSpacing: "var(--tr-title-1)", color: "var(--text-danger)",
      }}>Emergency skip</h1>
      {budgetSpent && (
        <p style={{ marginTop: 6, fontSize: "var(--fs-caption)", color: "var(--more)" }}>
          Over budget. Still goes through.
        </p>
      )}
      {workout && (
        <p style={{ marginTop: 6, fontSize: "var(--fs-caption)", color: "var(--text-body)" }}>
          Also ends workout mode.
        </p>
      )}
      <div style={{ marginTop: 16 }}><EmergencyViz /></div>
      <div style={{ marginTop: 16, textAlign: "left" }}>
        <Input
          label="Reason"
          value={reason}
          onChange={(e) => setReason(e.target.value.slice(0, 120))}
          placeholder="Fill this to proceed without waiting"
        />
      </div>
      <Actions>
        {filled
          ? <Primary danger onClick={() => onConfirm(reason)}>Pause 15 min</Primary>
          : <HoldToConfirm label="Hold to pause 15 min" onFire={() => onConfirm(reason)} />}
        <TextLink onClick={onCancel}>Never mind</TextLink>
      </Actions>
    </Cluster>
  );
}

/* ── Current lookalikes ─────────────────────────────────────────────── */

function CurrentRing({ left, total, size = 120 }) {
  const p = total <= 0 ? 1 : 1 - left / total;
  const r = (size / 2) - 6;
  const c = 2 * Math.PI * r;
  return (
    <div style={{ position: "relative", width: size, height: size, margin: "0 auto" }}>
      <svg width={size} height={size} style={{ transform: "rotate(-90deg)" }}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#2C2C35" strokeWidth="5" />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="#7FD1A6" strokeWidth="5"
          strokeDasharray={c} strokeDashoffset={c * (1 - p)} strokeLinecap="round" />
      </svg>
      <div style={{
        position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center",
        fontSize: 34, fontWeight: 300, color: "#ECEEF1",
      }}>{left > 0 ? left : "→"}</div>
    </div>
  );
}

function CurrentFriction({ onLeave, onContinue }) {
  const total = 5;
  const left = useCountdown(total, true);
  return (
    <div style={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "center", padding: 32, textAlign: "center" }}>
      <CurrentRing left={left} total={total} />
      <div style={{ marginTop: 32, fontSize: 22, fontWeight: 600 }}>Opening reddit.com</div>
      <div style={{ marginTop: 8, fontSize: 14, color: "#8A909A" }}>Was there something you actually wanted here?</div>
      <button type="button" disabled={left > 0} onClick={onContinue} style={{
        marginTop: 36, height: 48, border: 0, borderRadius: 24, cursor: left > 0 ? "not-allowed" : "pointer",
        background: left > 0 ? "#17372A" : "#7FD1A6", color: left > 0 ? "#7FD1A6" : "#06281B",
        fontSize: 16, fontWeight: 500, opacity: left > 0 ? 0.55 : 1,
      }}>{left > 0 ? "Wait…" : "Go ahead"}</button>
      <button type="button" onClick={onLeave} style={{
        marginTop: 8, height: 44, background: "none", border: 0, color: "#8A909A", fontSize: 15, cursor: "pointer",
      }}>Not really — take me back</button>
    </div>
  );
}

function CurrentBlock({ kind, history, onPause, onLeave, onClose }) {
  const website = kind === "quota";
  const reason = kind === "quota" ? "You have used up your time for now."
    : kind === "workout" ? "Workout in progress."
      : "Blocked by one of your policies.";
  const lift = kind === "quota" ? "More time in 47m" : kind === "hard" ? "Allowed again in 2h 14m" : "";
  const title = kind === "hard" ? "Instagram" : "reddit.com";
  return (
    <div style={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "center", padding: 28, textAlign: "center" }}>
      <div style={{ fontSize: 13, letterSpacing: "0.18em", color: "#6E7681" }}>ROTBLOCK</div>
      <div style={{ marginTop: 20, fontSize: 30, fontWeight: 700 }}>{title}</div>
      <div style={{ marginTop: 10, fontSize: 16, color: "#B6BCC4" }}>{reason}</div>
      {history && (
        <div style={{ marginTop: 16, fontSize: 13, color: "#6E7681", lineHeight: 1.45 }}>
          reddit.com: 6h 40m this week  ▲ 1h 8m more<br />
          All blocked: 14h 12m this week  ▲ 2h 52m more
        </div>
      )}
      {lift && <div style={{ marginTop: 24, fontSize: 15, color: "#7FD1A6" }}>{lift}</div>}
      <button type="button" onClick={website ? onLeave : onClose} style={{
        marginTop: 36, height: 48, border: 0, borderRadius: 14, cursor: "pointer",
        background: "#7FD1A6", color: "#0B1F17", fontSize: 16, fontWeight: 500,
      }}>{website ? "Leave this site" : "Close this app"}</button>
      {website && (
        <button type="button" onClick={onClose} style={{
          marginTop: 18, background: "none", border: 0, color: "#9BA3AD", fontSize: 15, cursor: "pointer",
        }}>Close this app</button>
      )}
      <button type="button" onClick={onPause} style={{
        marginTop: 14, background: "none", border: 0, color: "#6E7681", fontSize: 15, cursor: "pointer",
      }}>I actually need this</button>
    </div>
  );
}

function CurrentPauseChoose({ history, budgetSpent, onStart, onCancel }) {
  const [scope, setScope] = useState("Just reddit.com");
  const [dur, setDur] = useState("15 min");
  const scopes = ["Just reddit.com", "All of brainrot", "Everything"];
  const chip = (label, on, fn) => (
    <button key={label} type="button" onClick={fn} style={{
      height: 32, padding: "0 12px", borderRadius: 16, cursor: "pointer",
      border: `1px solid ${on ? "#7FD1A6" : "#2C2C35"}`,
      background: on ? "#17372A" : "transparent", color: on ? "#7FD1A6" : "#B6BCC4", fontSize: 13,
    }}>{label}</button>
  );
  return (
    <div style={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "center", padding: 28 }}>
      <div style={{ fontSize: 13, color: "#8A909A" }}>rotblock</div>
      <div style={{ marginTop: 12, fontSize: 24, fontWeight: 600 }}>Pause blocking for reddit.com?</div>
      <div style={{ marginTop: 8, fontSize: 14, color: "#8A909A" }}>
        Only what you pick below unblocks for a set time; everything else keeps blocking. It comes back on its own — you do not have to remember.
      </div>
      <div style={{ marginTop: 24, fontSize: 13, color: "#8A909A" }}>What to pause</div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 8, marginTop: 8 }}>{scopes.map((s) => chip(s, s === scope, () => setScope(s)))}</div>
      <div style={{ marginTop: 24, fontSize: 13, color: "#8A909A" }}>For how long</div>
      <div style={{ display: "flex", gap: 8, marginTop: 8 }}>{DURATIONS.map((d) => chip(d, d === dur, () => setDur(d)))}</div>
      <div style={{ marginTop: 16, fontSize: 12, color: budgetSpent ? "#E5484D" : "#8A909A" }}>
        {budgetSpent ? "4 of 4 pauses used today · 60 of 60 min used — budget spent" : "2 of 4 pauses used today · 25 of 60 min used"}
      </div>
      {history && (
        <div style={{ marginTop: 16, fontSize: 12, color: "#8A909A", lineHeight: 1.5 }}>
          This target  ·  Today: 1h 42m  ▲ 24m  ·  This week: 6h 40m  ▲ 1h 8m<br />
          All blocked  ·  Today: 3h 18m  ▲ 8m  ·  This week: 14h 12m  ▲ 2h 52m
        </div>
      )}
      <button type="button" onClick={onStart} style={{
        marginTop: 28, height: 48, border: 0, borderRadius: 24, cursor: "pointer",
        background: "#7FD1A6", color: "#06281B", fontSize: 16, fontWeight: 500,
      }}>{budgetSpent ? "Budget spent — emergency skip only" : "Wait 8s, then pause"}</button>
      <button type="button" onClick={onCancel} style={{
        marginTop: 4, height: 44, background: "none", border: 0, color: "#8A909A", fontSize: 15, cursor: "pointer",
      }}>Never mind</button>
    </div>
  );
}

function CurrentPauseCool({ onAbandon, onEmergency, onFinished }) {
  const total = 8;
  const left = useCountdown(total, true);
  useWhenZero(left, onFinished);
  return (
    <div style={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "center", padding: 28, textAlign: "center" }}>
      <CurrentRing left={left} total={total} size={140} />
      <div style={{ marginTop: 28, fontSize: 16 }}>Pausing for 15 min once this finishes.</div>
      <div style={{ marginTop: 8, fontSize: 14, color: "#8A909A" }}>If the urge passes before then, just leave.</div>
      <button type="button" onClick={onAbandon} style={{
        marginTop: 36, background: "none", border: 0, color: "#8A909A", fontSize: 15, cursor: "pointer",
      }}>I changed my mind</button>
      <button type="button" onClick={onEmergency} style={{
        marginTop: 8, background: "none", border: 0, color: "#E5484D", fontSize: 15, cursor: "pointer",
      }}>Skip the wait — I need this now</button>
    </div>
  );
}

function CurrentEmergency({ budgetSpent, onConfirm, onCancel }) {
  const [reason, setReason] = useState("");
  const [holding, setHolding] = useState(false);
  const [frac, setFrac] = useState(0);
  const start = useRef(0);
  useEffect(() => {
    if (!holding) { setFrac(0); return undefined; }
    start.current = performance.now();
    let raf;
    const tick = (now) => {
      const f = Math.min(1, (now - start.current) / 3000);
      setFrac(f);
      if (f >= 1) { setHolding(false); onConfirm(reason); return; }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [holding, onConfirm, reason]);
  return (
    <div style={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "center", padding: 28 }}>
      <div style={{ fontSize: 24, fontWeight: 600, color: "#E5484D" }}>Emergency skip</div>
      <div style={{ marginTop: 8, fontSize: 14, color: "#8A909A" }}>
        {budgetSpent
          ? "You are out of pause budget for today. This will still go through — it is just recorded as over budget."
          : "This skips the wait. It goes in the log with its own marker so you can see later how often you reached for it."}
      </div>
      <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="What do you need it for?"
        style={{
          marginTop: 20, height: 48, padding: "0 14px", borderRadius: 8,
          border: "1px solid #2C2C35", background: "#17171D", color: "#ECEEF1", fontSize: 15,
        }} />
      <button type="button"
        onMouseDown={() => setHolding(true)} onMouseUp={() => setHolding(false)} onMouseLeave={() => setHolding(false)}
        onTouchStart={(e) => { e.preventDefault(); setHolding(true); }}
        onTouchEnd={() => setHolding(false)}
        style={{
          position: "relative", marginTop: 24, height: 56, overflow: "hidden",
          border: 0, borderRadius: 28, cursor: "pointer",
          background: "#3A1416", color: "#FFB4B6", fontSize: 16, fontWeight: 500,
        }}>
        <span style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: (frac * 100) + "%", background: "#E5484D" }} />
        <span style={{ position: "relative" }}>Hold to pause 15 min</span>
      </button>
      <button type="button" onClick={onCancel} style={{
        marginTop: 4, height: 44, background: "none", border: 0, color: "#8A909A", fontSize: 15, cursor: "pointer",
      }}>Never mind</button>
    </div>
  );
}

function DoneFlash({ text, onClear }) {
  useEffect(() => {
    const id = setTimeout(onClear, 2200);
    return () => clearTimeout(id);
  }, [onClear]);
  return (
    <div style={{
      position: "absolute", left: 12, right: 12, bottom: 18, zIndex: 20,
      padding: "10px 12px", background: "var(--surface-raised)",
      border: "1px solid var(--border-default)", borderRadius: "var(--r-lg)",
      boxShadow: "var(--shadow-2)", fontSize: "var(--fs-body-sm)", color: "var(--text-heading)",
    }}>{text}</div>
  );
}

function RailGroup({ title, children }) {
  return (
    <div style={{ marginBottom: 18 }}>
      <div className="eyebrow" style={{ marginBottom: 6, padding: "0 2px" }}>{title}</div>
      <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>{children}</div>
    </div>
  );
}

function RailBtn({ on, children, onClick }) {
  return (
    <button type="button" onClick={onClick} style={{
      display: "flex", alignItems: "center", minHeight: 32, padding: "6px 8px",
      textAlign: "left", cursor: "pointer",
      background: on ? "var(--surface-accent-soft)" : "transparent",
      color: on ? "var(--on-accent-soft)" : "var(--text-body)",
      border: on ? "1px solid var(--orange-200)" : "1px solid transparent",
      borderRadius: "var(--r-sm)", fontSize: "var(--fs-body-sm)", fontWeight: on ? 500 : 400,
    }}>{children}</button>
  );
}

function Caption({ screen, look }) {
  const map = {
    friction: "Circle wait, centered. One question. Clocks for hours today — no paragraph.",
    "block-quota": "Reason is the headline. Clocks = hours today. The large more/less is versus your 30-day average, spelled out underneath. Bars are 30 days; y-axis is hours.",
    "block-hard": "Same layout. Headline is Blocked, not the app name.",
    "block-workout": "Headline is Workout. No lift time — a cap is not an allowance.",
    "pause-choose": "Declarative title. Scope is the X, dashed underline + ▼, narrowest by default.",
    "pause-cool": "Circle, one line, two text links. Skip stays text, not a button.",
    "pause-emergency": "30-day bars of emergency minutes. A reason skips the hold; blank still requires hold.",
  };
  return (
    <div>
      <p style={{ fontSize: "var(--fs-body)", color: "var(--text-body)", maxWidth: "36ch" }}>{map[screen]}</p>
      {look === "current" && (
        <p style={{ marginTop: 8, fontSize: "var(--fs-caption)", color: "var(--text-muted)", maxWidth: "36ch" }}>
          Shipping screens, for comparison.
        </p>
      )}
    </div>
  );
}

function Interstitials() {
  const [screen, setScreen] = useState("friction");
  const [look, setLook] = useState("proposed");
  const [history, setHistory] = useState(true);
  const [budgetSpent, setBudgetSpent] = useState(false);
  const [workout, setWorkout] = useState(false);
  const [flash, setFlash] = useState(null);
  const [flowKey, setFlowKey] = useState(0);

  const go = (id) => { setScreen(id); setFlowKey((k) => k + 1); };
  const toast = (t) => setFlash(t);
  const onLeave = () => toast("Left. Browser would move to the loopback page.");
  const onClose = () => toast("Home. The app is behind you.");
  const onContinue = () => toast("Wait served. The page opens.");
  const onPause = () => go("pause-choose");
  const onStartPause = () => { if (budgetSpent) go("pause-emergency"); else go("pause-cool"); };
  const onPaused = () => { go("pause-choose"); toast("Paused 15 min."); };

  const proposed = look === "proposed";
  const kind = screen.startsWith("block-") ? screen.slice(6) : null;

  let body = null;
  if (proposed) {
    if (screen === "friction") body = <ProposedFriction key={flowKey} history={history} onLeave={onLeave} onContinue={onContinue} />;
    else if (screen.startsWith("block-")) body = <ProposedBlock key={flowKey} kind={kind} history={history} onPause={onPause} onLeave={onLeave} onClose={onClose} />;
    else if (screen === "pause-choose") body = <ProposedPauseChoose key={flowKey} history={history} budgetSpent={budgetSpent} onStart={onStartPause} onCancel={onClose} />;
    else if (screen === "pause-cool") body = <ProposedPauseCool key={flowKey} onAbandon={() => go("pause-choose")} onEmergency={() => go("pause-emergency")} onFinished={onPaused} />;
    else if (screen === "pause-emergency") body = <ProposedEmergency key={flowKey} budgetSpent={budgetSpent} workout={workout} onConfirm={onPaused} onCancel={() => go("pause-choose")} />;
  } else if (screen === "friction") body = <CurrentFriction key={flowKey} onLeave={onLeave} onContinue={onContinue} />;
  else if (screen.startsWith("block-")) body = <CurrentBlock key={flowKey} kind={kind} history={history} onPause={onPause} onLeave={onLeave} onClose={onClose} />;
  else if (screen === "pause-choose") body = <CurrentPauseChoose key={flowKey} history={history} budgetSpent={budgetSpent} onStart={onStartPause} onCancel={onClose} />;
  else if (screen === "pause-cool") body = <CurrentPauseCool key={flowKey} onAbandon={() => go("pause-choose")} onEmergency={() => go("pause-emergency")} onFinished={onPaused} />;
  else if (screen === "pause-emergency") body = <CurrentEmergency key={flowKey} budgetSpent={budgetSpent} onConfirm={onPaused} onCancel={() => go("pause-choose")} />;

  return (
    <div style={{
      display: "grid", gridTemplateColumns: "232px 1fr 280px",
      minHeight: "100vh", background: "var(--paper-2)",
    }}>
      <aside style={{
        padding: "20px 14px", borderRight: "1px solid var(--ink-rule)",
        background: "var(--paper-1)",
      }}>
        <div className="eyebrow">rotblock</div>
        <h1 style={{ fontSize: 20, marginTop: 4 }}>Interstitials</h1>
        <p style={{ marginTop: 6, fontSize: "var(--fs-caption)", color: "var(--text-muted)", maxWidth: "28ch" }}>
          Glanceable. Centered. Same actions.
        </p>
        <div style={{ marginTop: 18 }}>
          {["Friction", "Blocked", "Pause"].map((g) => (
            <RailGroup key={g} title={g}>
              {SCREENS.filter((s) => s.group === g).map((s) => (
                <RailBtn key={s.id} on={screen === s.id} onClick={() => go(s.id)}>{s.label}</RailBtn>
              ))}
            </RailGroup>
          ))}
        </div>
        <RailGroup title="Look">
          <RailBtn on={look === "proposed"} onClick={() => setLook("proposed")}>Proposed</RailBtn>
          <RailBtn on={look === "current"} onClick={() => setLook("current")}>Current</RailBtn>
        </RailGroup>
      </aside>

      <main style={{
        display: "flex", alignItems: "center", justifyContent: "center",
        padding: 28, background: "var(--paper-2)",
      }}>
        <Phone darkChrome={look === "current"}>
          <StatusBar />
          {body}
          {flash && <DoneFlash text={flash} onClear={() => setFlash(null)} />}
        </Phone>
      </main>

      <aside style={{
        padding: "20px 18px", borderLeft: "1px solid var(--ink-rule)",
        background: "var(--paper-1)", display: "flex", flexDirection: "column", gap: 16,
      }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <div className="eyebrow">Notes</div>
          {window.ThemeToggle && <window.ThemeToggle size="sm" />}
        </div>
        <Caption screen={screen} look={look} />
        <div>
          <div className="eyebrow" style={{ marginBottom: 8 }}>Sample state</div>
          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            <Switch checked={history} onChange={(e) => setHistory(e.target.checked)} label="Has usage history" />
            <Switch checked={budgetSpent} onChange={(e) => setBudgetSpent(e.target.checked)} label="Pause budget spent" />
            <Switch checked={workout} onChange={(e) => setWorkout(e.target.checked)} label="Workout running" />
          </div>
        </div>
        <div style={{ marginTop: "auto", fontSize: "var(--fs-caption)", color: "var(--text-faint)", maxWidth: "32ch" }}>
          Pale bar is everything blocked that day; dark bar is this app. Hour lines on the y-axis. Emergency chart uses 15-minute lines until that gets cramped, then hours.
          More/less is versus your 30-day average.
        </div>
      </aside>
    </div>
  );
}

Object.assign(window, { Interstitials });
})();
