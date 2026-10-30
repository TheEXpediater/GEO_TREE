import { BrainCircuit, Database, Smartphone } from "lucide-react";

import { PageHeader } from "@/components/ui/Display";
import { Tabs } from "@/components/ui/Navigation";
import { useUrlState } from "@/lib/hooks";

import { DatasetTab } from "./DatasetTab";
import { ModelTab } from "./ModelTab";
import { ReleaseTab } from "./ReleaseTab";

type Section = "release" | "model" | "dataset";

export function MobileSettingsPage() {
  const [state, setState] = useUrlState({ tab: "release" });
  const tab = (["release", "model", "dataset"].includes(state.tab) ? state.tab : "release") as Section;

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="Android app"
        title="Mobile Settings"
        description="The APK people download, its release history and QR code, and the leaf model and dataset behind it. Every value is read from the served file, the database or recorded evaluation reports."
      />
      <Tabs<Section>
        label="Mobile Settings sections"
        value={tab}
        onChange={(next) => setState({ tab: next })}
        items={[
          { id: "release", label: "App release", icon: Smartphone },
          { id: "model", label: "AI model", icon: BrainCircuit },
          { id: "dataset", label: "Dataset", icon: Database },
        ]}
      />
      <div role="tabpanel" aria-label={tab}>
        {tab === "release" ? <ReleaseTab /> : tab === "model" ? <ModelTab /> : <DatasetTab />}
      </div>
    </div>
  );
}
