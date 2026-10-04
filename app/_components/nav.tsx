import { Server, ChevronDown2 } from "pixelarticons/react";

export default function Nav() {
  return (
    <nav className="flex min-w-screen h-18 border-solid border-b-2 border-outline py-3 px-8">
      <div className="flex gap-8 h-full w-full">
        <div className="h-full aspect-square bg-section hover:bg-section-hover hover:cursor-pointer color-primary flex justify-center items-center border-solid border-2 border-outline rounded-2xl">
          M
        </div>
        <div className="group flex h-full items-center gap-2 p-2 cursor-pointer">
          <Server className="color-primary size-8 shrink-0" />

          <div
            className="
                flex items-center gap-1 overflow-hidden whitespace-nowrap
                max-w-0 opacity-0 -translate-x-3
                transition-all duration-400 ease-out
                group-hover:max-w-64 group-hover:opacity-100 group-hover:translate-x-0
            "
          >
            <p className="color-primary">сервер</p>
            <ChevronDown2 className="size-6 shrink-0" />
          </div>
        </div>
      </div>
    </nav>
  );
}
