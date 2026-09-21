import { createFileRoute } from "@tanstack/react-router";
import { DanzhiApp } from "@/components/danzhi-app";

export const Route = createFileRoute("/")({ component: Home });

function Home() {
  return <DanzhiApp />;
}
