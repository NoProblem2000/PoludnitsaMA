import type { LiveStreamClientMessage, LiveStreamMessageDto } from './types';

export type SocketState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed';

interface Handlers {
  onMessage(message: LiveStreamMessageDto): void;
  onState(state: SocketState): void;
}

const MAX_BACKOFF_MS = 15_000;
const PING_INTERVAL_MS = 25_000;

/**
 * Client of {@code /ws/live}: one connection, at most one subscribed PID, automatic reconnect with exponential
 * backoff and re-subscription, keep-alive pings so proxies do not drop an idle connection.
 */
export class LiveSocket {
  private socket: WebSocket | null = null;
  private pid: number | null = null;
  private attempt = 0;
  private reconnectTimer: number | undefined;
  private pingTimer: number | undefined;
  private closedByUser = false;

  constructor(private readonly handlers: Handlers) {}

  subscribe(pid: number): void {
    this.pid = pid;
    this.closedByUser = false;
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.send({ action: 'subscribe', pid });
    } else if (!this.socket || this.socket.readyState === WebSocket.CLOSED) {
      this.connect();
    }
  }

  unsubscribe(): void {
    this.pid = null;
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.send({ action: 'unsubscribe' });
    }
  }

  close(): void {
    this.closedByUser = true;
    this.pid = null;
    window.clearTimeout(this.reconnectTimer);
    window.clearInterval(this.pingTimer);
    this.socket?.close();
    this.socket = null;
    this.handlers.onState('closed');
  }

  private connect(): void {
    window.clearTimeout(this.reconnectTimer);
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${scheme}://${window.location.host}/ws/live`);
    this.socket = socket;
    this.handlers.onState(this.attempt === 0 ? 'connecting' : 'reconnecting');

    socket.onopen = () => {
      this.attempt = 0;
      this.handlers.onState('open');
      if (this.pid != null) {
        this.send({ action: 'subscribe', pid: this.pid });
      }
      window.clearInterval(this.pingTimer);
      this.pingTimer = window.setInterval(() => this.send({ action: 'ping' }), PING_INTERVAL_MS);
    };
    socket.onmessage = (event: MessageEvent<string>) => {
      try {
        this.handlers.onMessage(JSON.parse(event.data) as LiveStreamMessageDto);
      } catch {
        // malformed frame: ignore, the next sample follows within a second
      }
    };
    socket.onclose = () => {
      window.clearInterval(this.pingTimer);
      if (this.socket !== socket) {
        return;
      }
      this.socket = null;
      if (this.closedByUser) {
        return;
      }
      const delay = Math.min(MAX_BACKOFF_MS, 500 * 2 ** this.attempt);
      this.attempt++;
      this.handlers.onState('reconnecting');
      this.reconnectTimer = window.setTimeout(() => this.connect(), delay);
    };
    socket.onerror = () => {
      // onclose follows and schedules the reconnect
    };
  }

  private send(message: LiveStreamClientMessage): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    }
  }
}
