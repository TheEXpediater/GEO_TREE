import { Eye, EyeOff, LockKeyhole, Smartphone } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";

import { ApiError } from "@/api/client";
import { useAuth } from "@/auth/AuthContext";
import { Wordmark } from "@/components/Logo";
import { Button } from "@/components/ui/Button";
import { Notice } from "@/components/ui/Display";
import { Field, Input } from "@/components/ui/Form";

export function LoginPage() {
  const { user, signIn, endedReason } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [errors, setErrors] = useState<{ email?: string; password?: string }>({});
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  const from = (location.state as { from?: string } | null)?.from ?? "/";
  if (user) return <Navigate to={from} replace />;

  async function submit(event: FormEvent) {
    event.preventDefault();
    const next: typeof errors = {};
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) next.email = "Enter a valid email address.";
    if (!password) next.password = "Enter your password.";
    setErrors(next);
    setFailure(null);
    if (next.email || next.password) return;
    setBusy(true);
    try {
      await signIn(email, password);
      navigate(from, { replace: true });
    } catch (error) {
      setFailure(error instanceof ApiError ? error : new ApiError(0, "UNKNOWN", "Sign-in failed. Try again."));
      setPassword("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-screen lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <section className="relative hidden overflow-hidden bg-forest lg:flex lg:flex-col lg:justify-between lg:p-12">
        <div className="survey-grid absolute inset-0 opacity-[0.35] [filter:invert(1)]" aria-hidden />
        <svg className="absolute -right-24 -top-24 h-[520px] w-[520px] text-cream/[0.06]" viewBox="0 0 200 200" aria-hidden>
          <circle cx="100" cy="100" r="96" fill="none" stroke="currentColor" strokeWidth="0.6" />
          <circle cx="100" cy="100" r="70" fill="none" stroke="currentColor" strokeWidth="0.6" />
          <circle cx="100" cy="100" r="44" fill="none" stroke="currentColor" strokeWidth="0.6" />
          <path d="M100 0v200M0 100h200" stroke="currentColor" strokeWidth="0.6" />
        </svg>
        <div className="relative">
          <Wordmark inverse />
        </div>
        <div className="relative max-w-md">
          <p className="text-2xs font-semibold uppercase tracking-[0.18em] text-[#D8C08A]">Tamarind field research</p>
          <h1 className="mt-3 text-3xl font-semibold leading-tight text-cream">Every tree, scan and training image in one place.</h1>
          <p className="mt-4 text-[15px] leading-relaxed text-cream/70">
            Review geotagged tree records from the field app, curate the DINOv2 leaf-classifier dataset, and follow every
            assessment over time.
          </p>
        </div>
        <p className="relative text-xs text-cream/45">Map data © OpenStreetMap contributors · GEO Tree capstone project</p>
      </section>

      <section className="flex items-center justify-center px-4 py-12 sm:px-8">
        <div className="w-full max-w-sm">
          <div className="mb-8 lg:hidden">
            <Wordmark />
          </div>
          <h2 className="text-2xl font-semibold">Sign in</h2>
          <p className="mt-1 text-sm text-ink-muted">Use your GEO Tree administrator account.</p>

          {endedReason && !failure ? <Notice className="mt-6" tone="sky" icon={LockKeyhole}>{endedReason}</Notice> : null}
          {failure ? (
            <Notice className="mt-6" tone={failure.code === "WEB_ADMIN_ONLY" ? "sky" : "red"} icon={failure.code === "WEB_ADMIN_ONLY" ? Smartphone : LockKeyhole}>
              {failure.message}
            </Notice>
          ) : null}

          <form className="mt-6 space-y-4" onSubmit={submit} noValidate>
            <Field label="Email" error={errors.email}>
              {(props) => (
                <Input {...props} type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="name@example.com" autoFocus />
              )}
            </Field>
            <Field label="Password" error={errors.password}>
              {(props) => (
                <div className="relative">
                  <Input
                    {...props}
                    type={showPassword ? "text" : "password"}
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="pr-11"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((v) => !v)}
                    aria-label={showPassword ? "Hide password" : "Show password"}
                    aria-pressed={showPassword}
                    className="absolute right-1 top-1/2 flex h-8 w-9 -translate-y-1/2 items-center justify-center rounded-md text-ink-faint hover:bg-sand hover:text-ink"
                  >
                    {showPassword ? <EyeOff className="h-4 w-4" aria-hidden /> : <Eye className="h-4 w-4" aria-hidden />}
                  </button>
                </div>
              )}
            </Field>
            <Button type="submit" variant="primary" className="w-full" loading={busy}>
              {busy ? "Signing in…" : "Sign in"}
            </Button>
          </form>
          <p className="mt-8 text-xs leading-relaxed text-ink-faint">
            Field Tester accounts sign in to the GEO Tree Android app. Forgot your password? Ask a GEO Tree administrator to reset it.
          </p>
        </div>
      </section>
    </div>
  );
}
