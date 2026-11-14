/*
 * 自定义字体注册（ArkUI 侧）。
 *
 * Kuikly 的文本走 C 侧字体适配器（entry/src/main/cpp/napi_init.cpp 的 FontTable），
 * 这里再用 UIContext.getFont().registerFont 注册一遍，保证 ArkUI 原生组件
 * （输入框、原生自定义 View）用同样的 family 名也能命中。两张表必须保持一致。
 *
 * family 名与 Flutter 参考工程 pubspec.yaml 一致；多字重 family 另有显式字面名
 * （Poppins-SemiBold 等，与 iOS PostScript 名一致）。字体文件在 resources/rawfile/fonts/。
 */
import { font, UIContext } from '@kit.ArkUI';
import { SD, SDLog } from '../support/SDModuleSupport';

export class AppFonts {
  private static registered: boolean = false;

  static registerAll(uiContext: UIContext): void {
    if (AppFonts.registered) {
      return;
    }
    AppFonts.registered = true;
    const fonts: Array<font.FontOptions> = [
      { familyName: 'Poppins', familySrc: $rawfile('fonts/poppins_regular.ttf') },
      { familyName: 'Poppins-Thin', familySrc: $rawfile('fonts/poppins_thin.ttf') },
      { familyName: 'Poppins-Light', familySrc: $rawfile('fonts/poppins_light.ttf') },
      { familyName: 'Poppins-Regular', familySrc: $rawfile('fonts/poppins_regular.ttf') },
      { familyName: 'Poppins-Medium', familySrc: $rawfile('fonts/poppins_medium.ttf') },
      { familyName: 'Poppins-SemiBold', familySrc: $rawfile('fonts/poppins_semibold.ttf') },
      { familyName: 'Poppins-Bold', familySrc: $rawfile('fonts/poppins_bold.ttf') },
      { familyName: 'Montserrat', familySrc: $rawfile('fonts/Montserrat-Medium.otf') },
      { familyName: 'Montserrat-Medium', familySrc: $rawfile('fonts/Montserrat-Medium.otf') },
      { familyName: 'Montserrat-SemiBold', familySrc: $rawfile('fonts/Montserrat-SemiBold.otf') },
      { familyName: 'Montserrat-Bold', familySrc: $rawfile('fonts/Montserrat-Bold.otf') },
      { familyName: 'Anton', familySrc: $rawfile('fonts/anton_regular.ttf') },
      { familyName: 'Bangers', familySrc: $rawfile('fonts/Bangers.ttf') },
      { familyName: 'LemonMilk', familySrc: $rawfile('fonts/LemonMilk-1.otf') },
      { familyName: 'MomcakeBold', familySrc: $rawfile('fonts/MOMCAKE-Bold.ttf') },
      { familyName: 'MomcakeThin', familySrc: $rawfile('fonts/MOMCAKE-Thin.ttf') },
      { familyName: 'MonotonRegular', familySrc: $rawfile('fonts/Monoton-Regular.ttf') },
      { familyName: 'MontserratBlack', familySrc: $rawfile('fonts/Montserrat-Black.ttf') },
      { familyName: 'MontserratBlackItalic', familySrc: $rawfile('fonts/MontserratAlternates-BlackItalic.ttf') },
      { familyName: 'Roboto', familySrc: $rawfile('fonts/roboto_medium_numbers.ttf') },
      { familyName: 'Carter', familySrc: $rawfile('fonts/Carter-One.ttf') },
      { familyName: 'PaytoneOne', familySrc: $rawfile('fonts/PaytoneOne.ttf') }
    ];
    try {
      const uiFont = uiContext.getFont();
      for (let i = 0; i < fonts.length; i++) {
        try {
          uiFont.registerFont(fonts[i]);
        } catch (e) {
          SDLog.e('AppFonts', `registerFont ${fonts[i].familyName as string} failed: ${SD.errMsg(e as Object)}`);
        }
      }
    } catch (e) {
      SDLog.e('AppFonts', `getFont failed: ${SD.errMsg(e as Object)}`);
    }
  }
}
