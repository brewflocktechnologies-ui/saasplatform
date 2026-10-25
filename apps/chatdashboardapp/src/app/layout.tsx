import '@chat-platform/designsystem/tokens.css';
import './global.css';

export const metadata = {
  title: 'Chat Platform — Local Development',
  description: 'Local scaffold validation dashboard for the chat platform.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
