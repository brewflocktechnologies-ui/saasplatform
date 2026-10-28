'use client';

import { CONNECTION_LABEL, type Availability, type ConnectionState } from '@chat-platform/chat-client';

const time = (iso?: string | null) => (iso ? new Date(iso).toLocaleTimeString() : '—');

export function PresenceControl(props: {
  availability: Availability;
  lastHeartbeatAt: string | null;
  connection: ConnectionState;
  onChange: (availability: Availability) => void;
}) {
  return (
    <div className="presence">
      <label>
        Availability{' '}
        <select
          data-testid="availability-select"
          value={props.availability}
          onChange={(e) => props.onChange(e.target.value as Availability)}
        >
          <option value="ONLINE">Online</option>
          <option value="AWAY">Away</option>
          <option value="OFFLINE">Offline</option>
        </select>
      </label>{' '}
      <span className="muted" data-testid="last-heartbeat">
        Last heartbeat: {time(props.lastHeartbeatAt)}
      </span>{' '}
      <span className="conn" data-testid="connection-state" data-state={props.connection} role="status">
        {CONNECTION_LABEL[props.connection]}
      </span>
    </div>
  );
}
