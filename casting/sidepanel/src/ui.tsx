import type { ReactNode } from "react";
import { Icon, type IconName } from "./icons";
import { showTab } from "./state";
import type { Problem } from "./types";

export function IconButton({ icon, label, onClick, disabled, active, className }: {
  icon: IconName; label: string; onClick?: () => void; disabled?: boolean; active?: boolean; className?: string;
}) {
  return (
    <button type="button" className={`icon-button${active ? " on" : ""}${className ? ` ${className}` : ""}`}
      aria-label={label} title={label} onClick={onClick} disabled={disabled}>
      <Icon name={icon} />
    </button>
  );
}

export function Spinner({ className }: { className?: string }) {
  return <span className={`spinner${className ? ` ${className}` : ""}`} aria-hidden="true" />;
}

/** A labelled row: an icon and name on the left, its control on the right. */
export function Field({ icon, label, children }: { icon: IconName; label: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label"><Icon name={icon} />{label}</span>
      {children}
    </label>
  );
}

export interface Option {
  value: string;
  label: string;
}

export function Select({ value, options, onChange, disabled, label }: {
  value: string; options: Option[]; onChange: (value: string) => void; disabled?: boolean; label?: string;
}) {
  return (
    <span className="select">
      <select value={value} disabled={disabled} aria-label={label} onChange={event => onChange(event.target.value)}>
        {options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
      </select>
    </span>
  );
}

export function Segmented<T extends string>({ value, options, onChange, label }: {
  value: T; options: { value: T; label: ReactNode; title?: string }[]; onChange: (value: T) => void; label: string;
}) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map(option => (
        <button key={option.value} type="button" aria-pressed={option.value === value} title={option.title}
          onClick={() => onChange(option.value)}>{option.label}</button>
      ))}
    </div>
  );
}

export function Switch({ checked, onChange, label }: { checked: boolean; onChange: (checked: boolean) => void; label: string }) {
  return <input type="checkbox" role="switch" className="switch" aria-label={label} checked={checked}
    onChange={event => onChange(event.target.checked)} />;
}

/** A calm state message: nothing found, offline, protected video… */
export function Empty({ icon, title, children, action, tone }: {
  icon: IconName; title: string; children?: ReactNode; action?: ReactNode; tone?: "warning";
}) {
  return (
    <div className={`empty${tone ? ` ${tone}` : ""}`}>
      <span className="empty-icon"><Icon name={icon} /></span>
      <strong>{title}</strong>
      {children && <p>{children}</p>}
      {action}
    </div>
  );
}

/** A failure the viewer can act on. Technical details live in Developer. */
export function ProblemNote({ problem, onDismiss }: { problem: Problem; onDismiss?: () => void }) {
  return (
    <div className="problem" role="status">
      <Icon name="alert" />
      <div className="grow">
        <strong>{problem.title}</strong>
        {problem.message && <p>{problem.message}</p>}
        <button type="button" className="link small" onClick={() => showTab("developer")}>Details</button>
      </div>
      {onDismiss && <IconButton icon="close" label="Dismiss" className="small" onClick={onDismiss} />}
    </div>
  );
}

export function Section({ title, children, action }: { title?: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="card">
      {(title || action) && (
        <div className="card-head">
          {title && <h2>{title}</h2>}
          {action}
        </div>
      )}
      {children}
    </section>
  );
}
