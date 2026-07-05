import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import OtpInput from './OtpInput.vue';

vi.mock('../api/auth', () => ({
  verifyEmail: vi.fn(),
  verifyOtp: vi.fn(),
  resendOtp: vi.fn(),
}));

import { verifyEmail, resendOtp } from '../api/auth';

const mockedVerifyEmail = vi.mocked(verifyEmail);
const mockedResendOtp = vi.mocked(resendOtp);

describe('OtpInput', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockedVerifyEmail.mockReset();
    mockedResendOtp.mockReset();
  });

  it('renders 6 digit inputs', () => {
    const wrapper = mount(OtpInput, {
      props: { email: 'a@b.com', purpose: 'register' },
    });

    expect(wrapper.findAll('input').length).toBe(6);
  });

  it('emits verified event on successful verification', async () => {
    mockedVerifyEmail.mockResolvedValueOnce({ email: 'a@b.com', role: 'user' });

    const wrapper = mount(OtpInput, {
      props: { email: 'a@b.com', purpose: 'register' },
    });

    const inputs = wrapper.findAll('input');
    for (let i = 0; i < 6; i++) {
      await inputs[i].setValue(String(i + 1));
    }

    await wrapper.find('button[type="button"]').trigger('click');
    await wrapper.vm.$nextTick();

    expect(mockedVerifyEmail).toHaveBeenCalledWith('a@b.com', '123456');
    expect(wrapper.emitted('verified')).toBeTruthy();
  });

  it('resends otp when resend button is clicked', async () => {
    mockedResendOtp.mockResolvedValueOnce(undefined);

    const wrapper = mount(OtpInput, {
      props: { email: 'a@b.com', purpose: 'register' },
    });

    // 先让 cooldown 过去
    vi.advanceTimersByTime(60000);
    await wrapper.vm.$nextTick();

    const buttons = wrapper.findAll('button[type="button"]');
    const resendButton = buttons.find((b) => b.text().includes('重新发送'));
    expect(resendButton).toBeTruthy();
    await resendButton!.trigger('click');

    expect(mockedResendOtp).toHaveBeenCalledWith('a@b.com');
  });
});
