import { useState } from 'react';
import '../styles/global.css';

const greetings = [
  '你好，我是 GenWriter',
  '嗨，我是你的 AI 写作助手',
  '你好，我是 GenWriter',
  '嗨，我是 GenWriter',
  '你好，很高兴见到你',
];

const subtitles = [
  '你的 AI 写作助手。',
  '随时为你效劳。',
  '我们马上开始吧。',
  '专注创作，其余交给我。',
];

const WelcomeScreen = () => {
  const [greeting] = useState(() => greetings[Math.floor(Math.random() * greetings.length)]);
  const [subtitle] = useState(() => subtitles[Math.floor(Math.random() * subtitles.length)]);

  return (
    <div className="welcome-screen">
      <div className="welcome-content">
        <div className="welcome-logo">
          <img src="/logo.jpg" alt="GenWriter" width="88" height="88" />
        </div>
        <h1 className="welcome-title">{greeting}</h1>
        <p className="welcome-subtitle">{subtitle}</p>

      </div>
    </div>
  );
};

export default WelcomeScreen;
