import type { ReactNode } from 'react';

export interface NoticeProps {
  role?: 'alert' | 'status';
  children: ReactNode;
}

/** Neutral notice box (DESIGN.md notice): never red, it is not the shopper's input error. */
export function Notice({ role, children }: NoticeProps) {
  return (
    <p className="notice" role={role}>
      {children}
    </p>
  );
}
