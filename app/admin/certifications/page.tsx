"use client";
import CrudEditor from "@/components/admin/CrudEditor";

export default function Page() {
  return (
    <CrudEditor
      table="certifications"
      title="certificate"
      emptyHint="No certificates uploaded. Only add ones you actually hold."
      fields={[
        { key: "title", label: "Certificate title" },
        { key: "issuer", label: "Issuer" },
        { key: "year", label: "Year", type: "number" },
        { key: "file_url", label: "PDF or image", type: "file", bucket: "media" },
      ]}
    />
  );
}
