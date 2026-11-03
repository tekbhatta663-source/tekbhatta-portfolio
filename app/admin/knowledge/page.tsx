"use client";
import CrudEditor from "@/components/admin/CrudEditor";

export default function Page() {
  return (
    <CrudEditor
      table="chatbot_kb"
      title="fact"
      emptyHint="These facts are what Tek AI is allowed to say. Anything missing stays unanswered."
      fields={[
        { key: "question", label: "Topic or question" },
        { key: "keywords", label: "Trigger words (comma separated)", type: "tags", placeholder: "study, bbs, college" },
        { key: "answer", label: "Verified answer", type: "textarea" },
      ]}
    />
  );
}
