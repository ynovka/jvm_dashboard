import Application from "../../_components/application";
export default async function Page({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  return <Application appId={id} />;
}
