import { KeyRound, Pause, Pencil, Play, Plus, ShieldCheck, Users as UsersIcon, Wand2 } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";

import { ApiError } from "@/api/client";
import { useCreateUser, useResetPassword, useUpdateUser, useUsers } from "@/api/hooks";
import type { Role, User } from "@/api/types";
import { useAuth } from "@/auth/AuthContext";
import { Button } from "@/components/ui/Button";
import { Badge, Card, EmptyState, ErrorState, Notice, PageHeader, Skeleton, errorMessage, type Tone } from "@/components/ui/Display";
import { Field, Input, SearchInput, Select } from "@/components/ui/Form";
import { OverflowMenu, Pagination, Table, Td, Th } from "@/components/ui/Navigation";
import { ConfirmDialog, Dialog } from "@/components/ui/Overlay";
import { useToast } from "@/components/ui/Toast";
import { useSearchBox, useUrlState } from "@/lib/hooks";
import { formatDate, formatRelative, humanize } from "@/lib/format";

const ROLE_TONES: Record<Role, Tone> = { SUPER_ADMIN: "forest", ADMIN: "green", FIELD_TESTER: "sky" };
const ROLE_HELP: Record<Role, string> = {
  SUPER_ADMIN: "Everything, including managing administrators",
  ADMIN: "Field records, tags, classifier, assessments, Field Tester accounts",
  FIELD_TESTER: "Android field app only",
};
const MIN_PASSWORD = 10;

function assignableRoles(actorRole: Role | null): Role[] {
  return actorRole === "SUPER_ADMIN" ? ["FIELD_TESTER", "ADMIN", "SUPER_ADMIN"] : ["FIELD_TESTER"];
}

/** A random password made in the browser (Web Crypto); it is shown once to pass to the user. */
function generatePassword(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  const bytes = new Uint32Array(14);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join("");
}

function PasswordInput({ value, onChange, props }: { value: string; onChange: (v: string) => void; props: { id: string; "aria-describedby"?: string; invalid: boolean } }) {
  return (
    <div className="flex gap-2">
      <Input {...props} type="text" autoComplete="new-password" spellCheck={false} value={value} onChange={(e) => onChange(e.target.value)} className="font-mono" />
      <Button icon={Wand2} onClick={() => onChange(generatePassword())}>Generate</Button>
    </div>
  );
}

function UserDialog({ open, user, onClose }: { open: boolean; user: User | null; onClose: () => void }) {
  const { user: actor } = useAuth();
  const create = useCreateUser();
  const update = useUpdateUser();
  const toast = useToast();
  const roles = assignableRoles(actor?.role ?? null);
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState<Role>("FIELD_TESTER");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!open) return;
    setEmail(user?.email ?? "");
    setDisplayName(user?.displayName ?? "");
    setRole(user?.role ?? "FIELD_TESTER");
    setPassword(user ? "" : generatePassword());
    setErrors({});
  }, [open, user]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const next: Record<string, string> = {};
    if (!user && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) next.email = "Enter a valid email address.";
    if (!displayName.trim()) next.displayName = "Enter a display name.";
    if (!user && password.length < MIN_PASSWORD) next.password = `Use at least ${MIN_PASSWORD} characters.`;
    setErrors(next);
    if (Object.keys(next).length) return;
    try {
      if (user) {
        await update.mutateAsync({ id: user.id, displayName: displayName.trim(), ...(role !== user.role && !user.isSelf ? { role } : {}) });
        toast.success("Account updated");
      } else {
        await create.mutateAsync({ email: email.trim().toLowerCase(), displayName: displayName.trim(), role, password });
        toast.success("Account created", "Give the user their password through a private channel. It is not shown again.");
      }
      onClose();
    } catch (error) {
      if (error instanceof ApiError && error.code === "EMAIL_TAKEN") setErrors({ email: error.message });
      else toast.error("Couldn't save the account", errorMessage(error));
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={user ? `Edit ${user.email}` : "New account"}
      description={user ? undefined : "The user signs in with this email and password. Passwords are stored only as bcrypt hashes."}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" form="user-form" variant="primary" loading={create.isPending || update.isPending}>{user ? "Save" : "Create account"}</Button>
        </>
      }
    >
      <form id="user-form" onSubmit={submit} className="space-y-4" noValidate>
        {!user ? (
          <Field label="Email" required error={errors.email}>
            {(p) => <Input {...p} type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="off" data-autofocus />}
          </Field>
        ) : null}
        <Field label="Display name" required error={errors.displayName}>
          {(p) => <Input {...p} value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={80} />}
        </Field>
        <Field label="Role" hint={ROLE_HELP[role]}>
          {(p) => (
            <Select {...p} value={role} onChange={(e) => setRole(e.target.value as Role)} disabled={user?.isSelf || roles.length === 1}>
              {Array.from(new Set([...roles, ...(user ? [user.role] : [])])).map((r) => (
                <option key={r} value={r} disabled={!roles.includes(r)}>{humanize(r)}</option>
              ))}
            </Select>
          )}
        </Field>
        {user?.isSelf ? <p className="text-[13px] text-ink-faint">You cannot change your own role.</p> : null}
        {!user ? (
          <Field label="Initial password" required error={errors.password} hint={`At least ${MIN_PASSWORD} characters. Copy it now; it is not shown again.`}>
            {(p) => <PasswordInput value={password} onChange={setPassword} props={p} />}
          </Field>
        ) : null}
      </form>
    </Dialog>
  );
}

function ResetDialog({ user, onClose }: { user: User | null; onClose: () => void }) {
  const reset = useResetPassword();
  const toast = useToast();
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (user) {
      setPassword(generatePassword());
      setError(null);
    }
  }, [user]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!user) return;
    if (password.length < MIN_PASSWORD) return setError(`Use at least ${MIN_PASSWORD} characters.`);
    try {
      await reset.mutateAsync({ id: user.id, password });
      toast.success("Password reset", `${user.email} is signed out everywhere and must use the new password.`);
      onClose();
    } catch (err) {
      toast.error("Couldn't reset the password", errorMessage(err));
    }
  }

  return (
    <Dialog
      open={Boolean(user)}
      onClose={onClose}
      size="sm"
      title="Reset password"
      description={user ? `Set a new password for ${user.email}. Their existing sessions on every device end immediately.` : undefined}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" form="reset-form" variant="primary" loading={reset.isPending}>Reset password</Button>
        </>
      }
    >
      <form id="reset-form" onSubmit={submit} className="space-y-3" noValidate>
        <Field label="New password" error={error} hint="Pass it to the user through a private channel.">
          {(p) => <PasswordInput value={password} onChange={(v) => { setPassword(v); setError(null); }} props={p} />}
        </Field>
        {user?.isSelf ? <Notice tone="amber">You are resetting your own password: you will be signed out here too.</Notice> : null}
      </form>
    </Dialog>
  );
}

export function UsersPage() {
  const { user: actor } = useAuth();
  const [state, update] = useUrlState({ q: "", role: "", status: "", page: "1" });
  const [search, setSearch] = useSearchBox(state.q, (q) => update({ q }));
  const users = useUsers({ q: state.q, role: state.role, status: state.status, page: Number(state.page), pageSize: 25 });
  const setStatus = useUpdateUser();
  const toast = useToast();
  const [editing, setEditing] = useState<{ open: boolean; user: User | null }>({ open: false, user: null });
  const [resetting, setResetting] = useState<User | null>(null);
  const [suspending, setSuspending] = useState<User | null>(null);

  async function toggleActive(target: User) {
    try {
      await setStatus.mutateAsync({ id: target.id, active: !target.active });
      toast.success(target.active ? `${target.email} suspended` : `${target.email} reactivated`, target.active ? "Their sessions stop working immediately." : undefined);
      setSuspending(null);
    } catch (error) {
      toast.error("Couldn't change the account status", errorMessage(error));
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Administration"
        title="Users"
        description={actor?.role === "SUPER_ADMIN" ? "Manage administrator and Field Tester accounts." : "Admins manage Field Tester accounts; administrator accounts are managed by a Super Admin."}
        actions={<Button variant="primary" icon={Plus} onClick={() => setEditing({ open: true, user: null })}>New account</Button>}
      />
      <Card>
        <div className="flex flex-col gap-3 border-b border-line p-4 sm:flex-row">
          <SearchInput className="sm:w-72" label="Search accounts" placeholder="Search name or email…" value={search} onChange={setSearch} />
          <div className="grid grid-cols-2 gap-3 sm:ml-auto sm:flex">
            <Select aria-label="Filter by role" value={state.role} onChange={(e) => update({ role: e.target.value })} className="sm:w-44">
              <option value="">All roles</option>
              <option value="SUPER_ADMIN">Super Admin</option>
              <option value="ADMIN">Admin</option>
              <option value="FIELD_TESTER">Field Tester</option>
            </Select>
            <Select aria-label="Filter by status" value={state.status} onChange={(e) => update({ status: e.target.value })} className="sm:w-40">
              <option value="">Any status</option>
              <option value="active">Active</option>
              <option value="suspended">Suspended</option>
            </Select>
          </div>
        </div>
        {users.isLoading ? (
          <div className="space-y-3 p-4">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-12" />)}</div>
        ) : users.error ? (
          <ErrorState className="m-4" error={users.error} onRetry={() => users.refetch()} />
        ) : !users.data || users.data.items.length === 0 ? (
          <EmptyState className="m-4" icon={UsersIcon} title="No accounts match" description="Try another search or filter." />
        ) : (
          <>
            <Table label="User accounts">
              <thead>
                <tr>
                  <Th>Account</Th>
                  <Th>Role</Th>
                  <Th>Status</Th>
                  <Th>Last sign-in</Th>
                  <Th>Created</Th>
                  <Th><span className="sr-only">Actions</span></Th>
                </tr>
              </thead>
              <tbody className={users.isFetching ? "opacity-70" : undefined}>
                {users.data.items.map((u) => (
                  <tr key={u.id} className="hover:bg-cream">
                    <Td>
                      <p className="font-medium">{u.displayName}{u.isSelf ? <span className="ml-1.5 text-xs font-normal text-ink-faint">(you)</span> : null}</p>
                      <p className="text-[13px] text-ink-muted">{u.email}</p>
                    </Td>
                    <Td>
                      <div className="flex flex-wrap gap-1">
                        <Badge tone={ROLE_TONES[u.role]}>{u.role === "SUPER_ADMIN" ? <ShieldCheck className="h-3 w-3" aria-hidden /> : null}{humanize(u.role)}</Badge>
                        {!u.roleAssigned ? <Badge tone="neutral">Legacy (no role)</Badge> : null}
                        {u.temporary ? <Badge tone="amber">Temporary</Badge> : null}
                      </div>
                    </Td>
                    <Td>{u.active ? <Badge tone="green" dot>Active</Badge> : <Badge tone="red" dot>Suspended</Badge>}</Td>
                    <Td className="text-[13px] text-ink-muted">{formatRelative(u.lastLoginAt)}</Td>
                    <Td className="text-[13px] text-ink-muted">{formatDate(u.createdAt)}</Td>
                    <Td align="right">
                      {u.canManage || u.isSelf ? (
                        <OverflowMenu
                          label={`Actions for ${u.email}`}
                          items={[
                            { label: "Edit account", icon: Pencil, onSelect: () => setEditing({ open: true, user: u }) },
                            { label: "Reset password", icon: KeyRound, onSelect: () => setResetting(u) },
                            ...(u.isSelf ? [] : [u.active
                              ? { label: "Suspend account", icon: Pause, danger: true, onSelect: () => setSuspending(u) }
                              : { label: "Reactivate account", icon: Play, onSelect: () => toggleActive(u) }]),
                          ]}
                        />
                      ) : (
                        <span className="text-xs text-ink-faint">Super Admin only</span>
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <div className="border-t border-line px-4 py-3">
              <Pagination page={users.data.page} pageSize={users.data.pageSize} total={users.data.total} onPage={(page) => update({ page: String(page) }, false)} noun="accounts" />
            </div>
          </>
        )}
      </Card>
      <Notice tone="sky" icon={KeyRound} title="Password resets are admin-assisted">
        There is no email reset system. An administrator sets a new password here and passes it to the user privately; their old sessions end at once.
      </Notice>
      <UserDialog open={editing.open} user={editing.user} onClose={() => setEditing({ open: false, user: null })} />
      <ResetDialog user={resetting} onClose={() => setResetting(null)} />
      <ConfirmDialog
        open={Boolean(suspending)}
        onCancel={() => setSuspending(null)}
        onConfirm={() => suspending && toggleActive(suspending)}
        busy={setStatus.isPending}
        title={`Suspend ${suspending?.email}?`}
        confirmLabel="Suspend account"
      >
        <p>They are signed out of the web and the Android app immediately and cannot sign in until reactivated.</p>
        <p>Their trees and assessments stay in place and keep their author.</p>
      </ConfirmDialog>
    </div>
  );
}
