/**
 * Minimal fetch wrapper for the GEO Tree API (same origin). Adds the bearer token, parses the
 * structured error body {"error": {code, message, details}}, and reports 401s so the session
 * can end cleanly. The server enforces every permission; nothing here is a security boundary.
 */

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const SESSION_KEY = "geotree.admin.session";

export interface StoredSession {
  token: string;
  expiresAt: string;
  user: { email: string; display_name: string; role: string | null };
}

export function readSession(): StoredSession | null {
  try {
    const raw = window.localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as StoredSession;
    if (!session.token || new Date(session.expiresAt).getTime() <= Date.now()) return null;
    return session;
  } catch {
    return null;
  }
}

export function writeSession(session: StoredSession | null): void {
  try {
    if (session) window.localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    else window.localStorage.removeItem(SESSION_KEY);
  } catch {
    // Storage unavailable (private mode): the session lives in memory only.
  }
}

let unauthorizedHandler: ((error: ApiError) => void) | null = null;

export function onUnauthorized(handler: ((error: ApiError) => void) | null): void {
  unauthorizedHandler = handler;
}

function authHeaders(): Record<string, string> {
  const session = readSession();
  return session ? { Authorization: `Bearer ${session.token}` } : {};
}

async function toApiError(response: Response): Promise<ApiError> {
  let code = "HTTP_ERROR";
  let message = `Request failed (${response.status}).`;
  let details: Record<string, unknown> = {};
  try {
    const body = await response.json();
    if (body?.error) {
      code = body.error.code ?? code;
      message = body.error.message ?? message;
      details = body.error.details ?? {};
    }
  } catch {
    // Not JSON (e.g. a proxy error page): keep the generic message.
  }
  if (response.status === 502 || response.status === 504) {
    message = "The GEO Tree server is not reachable right now. Try again shortly.";
  }
  return new ApiError(response.status, code, message, details);
}

export interface RequestOptions {
  method?: string;
  json?: unknown;
  body?: BodyInit;
  query?: Record<string, string | number | boolean | null | undefined>;
  signal?: AbortSignal;
  /** Skip the global sign-out on 401 (used by the login call itself). */
  allowUnauthorized?: boolean;
}

export function buildUrl(path: string, query?: RequestOptions["query"]): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `${path}?${text}` : path;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json", ...authHeaders() };
  let body = options.body;
  if (options.json !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.json);
  }
  let response: Response;
  try {
    response = await fetch(buildUrl(path, options.query), { method: options.method ?? "GET", headers, body, signal: options.signal });
  } catch (error) {
    if ((error as Error).name === "AbortError") throw error;
    throw new ApiError(0, "NETWORK_ERROR", "Cannot reach the GEO Tree server. Check your connection.");
  }
  if (!response.ok) {
    const error = await toApiError(response);
    if (response.status === 401 && !options.allowUnauthorized) unauthorizedHandler?.(error);
    throw error;
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/** Fetches a protected image (uploads require the bearer token) and returns an object URL. */
export async function fetchImageBlobUrl(path: string, signal?: AbortSignal): Promise<string> {
  const response = await fetch(path, { headers: authHeaders(), signal });
  if (!response.ok) throw await toApiError(response);
  return URL.createObjectURL(await response.blob());
}

/** Multipart upload with progress (fetch has no upload progress events). */
export function uploadWithProgress<T>(path: string, form: FormData, onProgress: (fraction: number) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", path);
    const headers = authHeaders();
    for (const [key, value] of Object.entries(headers)) xhr.setRequestHeader(key, value);
    xhr.setRequestHeader("Accept", "application/json");
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(event.loaded / event.total);
    };
    xhr.onerror = () => reject(new ApiError(0, "NETWORK_ERROR", "The upload was interrupted. Check your connection."));
    xhr.onload = () => {
      let body: any = null;
      try {
        body = JSON.parse(xhr.responseText);
      } catch {
        body = null;
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(body as T);
        return;
      }
      const error = new ApiError(xhr.status, body?.error?.code ?? "HTTP_ERROR", body?.error?.message ?? `Upload failed (${xhr.status}).`, body?.error?.details ?? {});
      if (xhr.status === 401) unauthorizedHandler?.(error);
      reject(error);
    };
    xhr.send(form);
  });
}

/** Downloads a protected file (bearer token required, so a plain link cannot be used). */
export async function downloadProtectedFile(path: string, fileName: string): Promise<void> {
  const response = await fetch(path, { headers: authHeaders() });
  if (!response.ok) throw await toApiError(response);
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
