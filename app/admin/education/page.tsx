"use client";
import CrudEditor from "@/components/admin/CrudEditor";

export default function Page() {
  return (
    <CrudEditor
      table="education"
      title="education entry"
      fields={[
        { key: "institution", label: "Institution" },
        { key: "programme", label: "Programme" },
        { key: "location", label: "Location" },
        { key: "start_year", label: "Start year", type: "number" },
        { key: "end_year", label: "End year", type: "number" },
        { key: "note", label: "Note", type: "textarea" },
      ]}
    />
  );
}
