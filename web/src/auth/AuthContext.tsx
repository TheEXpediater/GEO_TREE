import { useQueryClient } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

import { ApiError, onUnauthorized, readSession, request, writeSession, type StoredSession } from "@/api/client";
import type { LoginResponse, Role } from "@/api/types";

export interface CurrentUser {
  email: string;
  displayName: string;
  role: Role | null;
}

interface AuthState {
  user: CurrentUser | null;
  /** Why the last session ended (expired token, suspension), shown on the sign-in page. */
  endedReason: string | null;
  signIn: (email: string, password: string) => Promise<CurrentUser>;
  signOut: (reason?: string | null) => void;
}

const AuthContext = createContext<AuthState | null>(null);

export const ADMIN_ROLES: ReadonlyArray<Role> = ["SUPER_ADMIN", "ADMIN"];

export function isAdminRole(role: Role | null | undefined): boolean {
  return role === "SUPER_ADMIN" || role === "ADMIN";
}

function toUser(session: StoredSession | null): CurrentUser | null {
  if (!session) return null;
  return { email: session.user.email, displayName: session.user.display_name, role: (session.user.role as Role | null) ?? null };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [user, setUser] = useState<CurrentUser | null>(() => toUser(readSession()));
  const [endedReason, setEndedReason] = useState<string | null>(null);

  const signOut = useCallback(
    (reason: string | null = null) => {
      writeSession(null);
      setUser(null);
      setEndedReason(reason);
      queryClient.clear();
    },
    [queryClient],
  );

  useEffect(() => {
    onUnauthorized((error: ApiError) => {
      const reason = error.code === "ACCOUNT_SUSPENDED" ? error.message : "Your session ended. Sign in again to continue.";
      signOut(reason);
    });
    return () => onUnauthorized(null);
  }, [signOut]);

  // Pick up role or name changes made by another administrator since the last sign-in.
  useEffect(() => {
    if (!user) return;
    request<{ email: string; display_name: string; role: string | null }>("/api/v1/auth/me")
      .then((me) => {
        const session = readSession();
        if (!session) return;
        const next = { ...session, user: { ...session.user, display_name: me.display_name, role: me.role } };
        writeSession(next);
        setUser(toUser(next));
      })
      .catch(() => undefined);
    // Runs once per sign-in (keyed by email), not on every user object change.
  }, [user?.email]);

  const signIn = useCallback(async (email: string, password: string) => {
    const response = await request<LoginResponse>("/api/v1/auth/login", {
      method: "POST",
      json: { email: email.trim(), password },
      allowUnauthorized: true,
    });
    const session: StoredSession = { token: response.access_token, expiresAt: response.expires_at, user: response.user };
    const next = toUser(session)!;
    if (!isAdminRole(next.role)) {
      // Field Tester accounts work in the Android app only; nothing is stored for them here.
      throw new ApiError(403, "WEB_ADMIN_ONLY", "This account is for the GEO Tree Android field app. The web administration requires an Admin account.");
    }
    writeSession(session);
    setEndedReason(null);
    setUser(next);
    return next;
  }, []);

  const value = useMemo(() => ({ user, endedReason, signIn, signOut }), [user, endedReason, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>.");
  return value;
}
