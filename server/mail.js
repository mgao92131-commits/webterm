import nodemailer from 'nodemailer';

let transporter = null;
let isMockMode = false;

export function verifySmtpConfig() {
  const host = process.env.SMTP_HOST;
  const port = process.env.SMTP_PORT;
  const user = process.env.SMTP_USER;
  const pass = process.env.SMTP_PASS;
  const from = process.env.SMTP_FROM;

  const isProduction = process.env.NODE_ENV === 'production';
  const isTest = process.env.NODE_ENV === 'test';

  const missing = [];
  if (!host) missing.push('SMTP_HOST');
  if (!port) missing.push('SMTP_PORT');
  if (!user) missing.push('SMTP_USER');
  if (!pass) missing.push('SMTP_PASS');
  if (!from) missing.push('SMTP_FROM');

  if (missing.length > 0) {
    if (isProduction && !isTest) {
      console.error(`FATAL: SMTP configuration is missing: ${missing.join(', ')}`);
      process.exit(1);
    } else {
      console.warn(`[Mail] SMTP configuration is incomplete (missing ${missing.join(', ')}). Mail sending will fall back to console logging.`);
      isMockMode = true;
      return false;
    }
  }

  try {
    transporter = nodemailer.createTransport({
      host,
      port: Number(port),
      secure: Number(port) === 465,
      auth: {
        user,
        pass,
      },
    });
    isMockMode = false;
    return true;
  } catch (err) {
    if (isProduction && !isTest) {
      console.error('FATAL: Failed to initialize SMTP transport:', err.message);
      process.exit(1);
    } else {
      console.warn('[Mail] Failed to initialize SMTP transport. Falling back to console logging. Error:', err.message);
      isMockMode = true;
      return false;
    }
  }
}

export async function sendOtpEmail(toEmail, otpCode, purpose) {
  const purposeText = purpose === 'register' ? '注册账户激活' : '新设备登录验证';
  const subject = `[WebTerm] ${purposeText} 验证码`;
  
  const textContent = `您的验证码是: ${otpCode}

此验证码将在 10 分钟内有效，仅能使用一次。
如果这不是您本人的操作，请忽略此邮件。`;

  if (isMockMode || !transporter) {
    console.log('\n========================================');
    console.log(`[MOCK EMAIL SENT TO: ${toEmail}]`);
    console.log(`Subject: ${subject}`);
    console.log(`Code: ${otpCode}`);
    console.log('========================================\n');
    return { mock: true, code: otpCode };
  }

  const mailOptions = {
    from: process.env.SMTP_FROM,
    to: toEmail,
    subject,
    text: textContent,
  };

  try {
    const info = await transporter.sendMail(mailOptions);
    return { mock: false, messageId: info.messageId };
  } catch (err) {
    console.error(`[Mail] Failed to send email to ${toEmail}:`, err.message);
    throw err;
  }
}
