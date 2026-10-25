import styles from './components.module.css';

export type Status = 'loading' | 'up' | 'degraded' | 'down';

const STATUS_TEXT: Record<Status, string> = {
  loading: 'Checking…',
  up: 'Healthy',
  degraded: 'Degraded',
  down: 'Unavailable',
};

export interface StatusBadgeProps {
  /** What is being checked, e.g. "Backend API". Part of the accessible text. */
  label: string;
  status: Status;
}

/** Text + colour status indicator; never relies on colour alone. */
export function StatusBadge({ label, status }: StatusBadgeProps) {
  return (
    <span
      className={`${styles.badge} ${styles[status]}`}
      role="status"
      aria-live="polite"
      data-status={status}
    >
      <span className={styles.dot} aria-hidden="true" />
      {label}: {STATUS_TEXT[status]}
    </span>
  );
}
