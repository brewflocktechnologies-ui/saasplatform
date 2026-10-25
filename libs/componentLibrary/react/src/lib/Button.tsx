import type { ButtonHTMLAttributes } from 'react';
import styles from './components.module.css';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary';
}

export function Button({ variant = 'primary', className, type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      className={[styles.button, styles[variant], className].filter(Boolean).join(' ')}
      {...rest}
    />
  );
}
