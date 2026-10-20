import { Pencil, Plus, Tags as TagsIcon, Trash2 } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";

import { ApiError } from "@/api/client";
import { useDeleteTag, useSaveTag, useTags } from "@/api/hooks";
import type { Tag, TagColor } from "@/api/types";
import { Button, IconButton } from "@/components/ui/Button";
import { Card, EmptyState, ErrorState, PageHeader, Skeleton, errorMessage } from "@/components/ui/Display";
import { Field, Input, Textarea } from "@/components/ui/Form";
import { Table, Td, Th } from "@/components/ui/Navigation";
import { ConfirmDialog, Dialog } from "@/components/ui/Overlay";
import { useToast } from "@/components/ui/Toast";
import { cn } from "@/lib/cn";
import { formatCount, formatDate } from "@/lib/format";

import { TAG_SWATCHES, TagChip } from "./TagChip";

function TagDialog({ open, tag, onClose }: { open: boolean; tag: Tag | null; onClose: () => void }) {
  const toast = useToast();
  const save = useSaveTag();
  const [name, setName] = useState("");
  const [color, setColor] = useState<TagColor>("moss");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setName(tag?.name ?? "");
    setColor(tag?.color ?? "moss");
    setDescription(tag?.description ?? "");
    setError(null);
  }, [open, tag]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) {
      setError("Enter a tag name.");
      return;
    }
    try {
      await save.mutateAsync({ id: tag?.id, input: { name: name.trim(), color, description: description.trim() || null } });
      toast.success(tag ? "Tag updated" : "Tag created");
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.code === "TAG_NAME_CONFLICT") setError(err.message);
      else toast.error("Couldn't save the tag", errorMessage(err));
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={tag ? "Edit tag" : "New tag"}
      description="Tags organise tree records (e.g. plot, block, survey round). They are separate from the leaf-condition classes."
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" type="submit" form="tag-form" loading={save.isPending}>{tag ? "Save tag" : "Create tag"}</Button>
        </>
      }
    >
      <form id="tag-form" onSubmit={submit} className="space-y-4" noValidate>
        <Field label="Name" required error={error}>
          {(p) => <Input {...p} value={name} onChange={(e) => { setName(e.target.value); setError(null); }} maxLength={40} placeholder="e.g. North Block" data-autofocus />}
        </Field>
        <fieldset>
          <legend className="text-[13px] font-medium">Colour</legend>
          <div className="mt-2 flex flex-wrap gap-2" role="radiogroup" aria-label="Tag colour">
            {(Object.keys(TAG_SWATCHES) as TagColor[]).map((key) => (
              <button
                key={key}
                type="button"
                role="radio"
                aria-checked={color === key}
                aria-label={TAG_SWATCHES[key].label}
                onClick={() => setColor(key)}
                className={cn("flex h-9 w-9 items-center justify-center rounded-full border-2 transition-colors", color === key ? "border-forest" : "border-transparent hover:border-line-strong")}
              >
                <span className={cn("h-5 w-5 rounded-full", TAG_SWATCHES[key].dot)} />
              </button>
            ))}
          </div>
          <div className="mt-3"><TagChip tag={{ name: name.trim() || "Preview", color }} /></div>
        </fieldset>
        <Field label="Description" hint="Optional, up to 200 characters">
          {(p) => <Textarea {...p} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={200} className="min-h-[72px]" />}
        </Field>
      </form>
    </Dialog>
  );
}

export function TagsPage() {
  const tags = useTags();
  const remove = useDeleteTag();
  const toast = useToast();
  const [editing, setEditing] = useState<Tag | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [deleting, setDeleting] = useState<Tag | null>(null);

  const open = (tag: Tag | null) => {
    setEditing(tag);
    setDialogOpen(true);
  };

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Field data"
        title="Tags"
        description="Web-only labels for organising tree records. Field devices never change them."
        actions={<Button variant="primary" icon={Plus} onClick={() => open(null)}>New tag</Button>}
      />
      <Card>
        {tags.isLoading ? (
          <div className="space-y-3 p-4">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-12" />)}</div>
        ) : tags.error ? (
          <ErrorState className="m-4" error={tags.error} onRetry={() => tags.refetch()} />
        ) : !tags.data || tags.data.items.length === 0 ? (
          <EmptyState className="m-4" icon={TagsIcon} title="No tags yet" description="Create tags such as plot names or survey rounds, then assign them on a tree record." action={<Button variant="primary" icon={Plus} onClick={() => open(null)}>New tag</Button>} />
        ) : (
          <Table label="Tags">
            <thead>
              <tr>
                <Th>Tag</Th>
                <Th>Description</Th>
                <Th align="right">Trees</Th>
                <Th>Updated</Th>
                <Th><span className="sr-only">Actions</span></Th>
              </tr>
            </thead>
            <tbody>
              {tags.data.items.map((tag) => (
                <tr key={tag.id} className="hover:bg-cream">
                  <Td><TagChip tag={tag} className="text-[13px]" /></Td>
                  <Td className="max-w-md text-[13px] text-ink-muted">{tag.description ?? "—"}</Td>
                  <Td align="right" className="num">
                    {tag.treeCount ? <Link to={`/trees?tag=${tag.id}`} className="font-medium text-forest hover:underline">{formatCount(tag.treeCount)}</Link> : "0"}
                  </Td>
                  <Td className="text-[13px] text-ink-muted">{formatDate(tag.updatedAt)}</Td>
                  <Td align="right">
                    <div className="flex justify-end gap-1">
                      <IconButton icon={Pencil} label={`Edit ${tag.name}`} size="sm" onClick={() => open(tag)} />
                      <IconButton icon={Trash2} label={`Delete ${tag.name}`} size="sm" tone="danger" onClick={() => setDeleting(tag)} />
                    </div>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
      <TagDialog open={dialogOpen} tag={editing} onClose={() => setDialogOpen(false)} />
      <ConfirmDialog
        open={Boolean(deleting)}
        onCancel={() => setDeleting(null)}
        busy={remove.isPending}
        title={`Delete tag "${deleting?.name}"?`}
        confirmLabel="Delete tag"
        onConfirm={async () => {
          if (!deleting) return;
          try {
            const result = await remove.mutateAsync(deleting.id);
            toast.success("Tag deleted", `Removed from ${result.treesDetached} tree${result.treesDetached === 1 ? "" : "s"}.`);
            setDeleting(null);
          } catch (error) {
            toast.error("Couldn't delete the tag", errorMessage(error));
          }
        }}
      >
        <p>The tag is removed from {deleting?.treeCount ?? 0} tree record{deleting?.treeCount === 1 ? "" : "s"}. The trees themselves are not changed otherwise.</p>
      </ConfirmDialog>
    </div>
  );
}
