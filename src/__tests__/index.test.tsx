jest.mock('react-native-vision-camera', () => ({
  VisionCameraProxy: {
    initFrameProcessorPlugin: jest.fn(() => ({
      call: () => ({ text: 'mocked text' }),
    })),
  },
}));

import { performOcr } from '../index';

describe('@bear-block/vision-camera-ocr', () => {
  it('should export performOcr function', () => {
    expect(performOcr).toBeDefined();
    expect(typeof performOcr).toBe('function');
  });
});
