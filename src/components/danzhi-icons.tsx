"use client";

import { useState, type ButtonHTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/utils";

/** Cute 旦 mark — 日 is two stacked windows, 一 is a longer bar. */
export function DanMark({ className = "size-16" }: { className?: string }) {
  return (
    <svg viewBox="0 0 64 64" className={cn(className)} aria-hidden>
      <circle cx="32" cy="32" r="32" fill="#FF6B57" />
      <ellipse cx="32" cy="16" rx="18" ry="9" fill="#fff" opacity="0.16" />
      <rect x="19" y="8" width="26" height="30" rx="4" fill="none" stroke="#FFF6F0" strokeWidth="3.8" />
      <rect x="23" y="12" width="18" height="8" rx="1.6" fill="#FFB8AC" />
      <rect x="23" y="26" width="18" height="8" rx="1.6" fill="#FFB8AC" />
      <rect x="19" y="21.1" width="26" height="3.8" fill="#FFF6F0" />
      <rect x="12" y="46" width="40" height="5" rx="2.5" fill="#FFF6F0" />
    </svg>
  );
}

function Glyph({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={cn("size-6", className)}
      fill="currentColor"
      aria-hidden
    >
      {children}
    </svg>
  );
}

export function IconInbox({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path d="M4.2 6.8h15.6c.99 0 1.8.81 1.8 1.8v8.6c0 .99-.81 1.8-1.8 1.8H4.2c-.99 0-1.8-.81-1.8-1.8V8.6c0-.99.81-1.8 1.8-1.8Z" />
      <path d="M3.2 8.2 12 14.4 20.8 8.2" fill="none" stroke="#fff8f0" strokeWidth="1.7" strokeLinejoin="round" />
    </Glyph>
  );
}

export function IconTable({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path d="M8 3.2h2.2v3.2H8zM13.8 3.2H16v3.2h-2.2z" />
      <path
        fillRule="evenodd"
        d="M5 6.4h14c1.1 0 2 .9 2 2v11c0 1.1-.9 2-2 2H5c-1.1 0-2-.9-2-2v-11c0-1.1.9-2 2-2zm0 3.4v1.6h14V9.8H5zm1.7 3.8h3.1v3.1H6.7v-3.1zm5.2 0h3.1v3.1h-3.1v-3.1zm5.2 0H20v3.1h-2.9v-3.1z"
      />
    </Glyph>
  );
}

export function IconQr({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path
        fillRule="evenodd"
        d="M3.2 3.2h7.4v7.4H3.2V3.2zm2 2v3.4h3.4V5.2H5.2zM13.4 3.2h7.4v7.4h-7.4V3.2zm2 2v3.4h3.4V5.2h-3.4zM3.2 13.4h7.4v7.4H3.2v-7.4zm2 2v3.4h3.4v-3.4H5.2z"
      />
      <rect x="13.4" y="13.4" width="3.2" height="3.2" rx="0.4" />
      <rect x="17.6" y="17.6" width="3.2" height="3.2" rx="0.4" />
      <rect x="13.4" y="17.6" width="3.2" height="3.2" rx="0.4" />
      <rect x="17.6" y="13.4" width="3.2" height="3.2" rx="0.4" />
    </Glyph>
  );
}

export function IconDoor({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path d="M6 3.2h9.4c.9 0 1.6.7 1.6 1.6v14.4c0 .9-.7 1.6-1.6 1.6H6c-.9 0-1.6-.7-1.6-1.6V4.8c0-.9.7-1.6 1.6-1.6Z" />
      <circle cx="13.2" cy="12" r="1.1" fill="#fff8f0" />
    </Glyph>
  );
}

export function IconSettings({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path d="M10.2 2.6h3.6l.6 2.2 2.1.9 2.1-1.2 2.5 2.5-1.2 2.1.9 2.1 2.2.6v3.6l-2.2.6-.9 2.1 1.2 2.1-2.5 2.5-2.1-1.2-2.1.9-.6 2.2h-3.6l-.6-2.2-2.1-.9-2.1 1.2L3.2 17l1.2-2.1-.9-2.1L1.3 12.2V8.6l2.2-.6.9-2.1L3.2 3.8 5.7 1.3l2.1 1.2 2.1-.9.3 1z" />
      <circle cx="12" cy="12" r="3.1" fill="#fff8f0" />
    </Glyph>
  );
}

export function IconDownload({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path d="M11 3h2v10h3.2L12 18.2 7.8 13H11V3z" />
      <path d="M4 18h16v2.4H4z" />
    </Glyph>
  );
}

export function IconLogout({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path d="M4 5h8.5v2.2H6.2v9.6h6.3V19H4z" />
      <path d="M10 11h8.2l-2.4-2.4 1.5-1.5L22 12l-4.7 4.9-1.5-1.5 2.4-2.4H10z" />
    </Glyph>
  );
}

export function IconRefresh({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path
        d="M19.4 12a7.4 7.4 0 1 1-2.1-5.2"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.2"
        strokeLinecap="round"
      />
      <path d="M17.2 3.4v4.6h4.6" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
    </Glyph>
  );
}

export function IconEye({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path d="M2.4 12s3.4-6.2 9.6-6.2S21.6 12 21.6 12s-3.4 6.2-9.6 6.2S2.4 12 2.4 12Z" />
      <circle cx="12" cy="12" r="2.6" fill="#fff8f0" />
    </Glyph>
  );
}

export function IconEyeOff({ className }: { className?: string }) {
  return (
    <Glyph className={className}>
      <path d="M3.2 4.2 19.8 20.8l-1.4 1.4L1.8 5.6z" />
      <path d="M2.4 12s3.4-6.2 9.6-6.2c1.4 0 2.7.3 3.8.8L4.6 18.2C3.2 16.7 2.4 12 2.4 12Z" />
    </Glyph>
  );
}

export function IconSpinner({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={cn("size-5 animate-spin", className)} fill="none" aria-hidden>
      <circle cx="12" cy="12" r="8.5" stroke="currentColor" strokeOpacity=".22" strokeWidth="2.6" />
      <path d="M20.5 12a8.5 8.5 0 0 0-8.5-8.5" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" />
    </svg>
  );
}

export function PressButton({
  className,
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement>) {
  const [down, setDown] = useState(false);
  return (
    <button
      className={cn(className, down && "scale-[0.97]")}
      onPointerDown={() => setDown(true)}
      onPointerUp={() => setDown(false)}
      onPointerCancel={() => setDown(false)}
      {...rest}
    >
      {children}
    </button>
  );
}
