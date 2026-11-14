//
//  iosApp-Bridging-Header.h
//  暴露给 Swift 的 ObjC 头文件。
//
//  约定：
//  - Pod 的头文件一律用尖括号 <Pod/Header.h>（静态库 / 静态 framework 两种集成方式都能找到）；
//  - 工程内的头文件用相对本文件的路径 "Dir/Header.h"；
//  - 各自只在自己的分区里追加。
//

#import <UIKit/UIKit.h>

// --- kuikly ---
#import <OpenKuiklyIOSRender/KRBaseModule.h>
#import <OpenKuiklyIOSRender/KuiklyRenderView.h>
#import <OpenKuiklyIOSRender/KuiklyContextParam.h>
#import <OpenKuiklyIOSRender/KuiklyRenderModuleExportProtocol.h>
#import <OpenKuiklyIOSRender/KuiklyRenderViewExportProtocol.h>
#import <OpenKuiklyIOSRender/KRComponentDefine.h>
#import <OpenKuiklyIOSRender/UIView+CSS.h>
#import <OpenKuiklyIOSRender/KRConvertUtil.h>
#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import <SDWebImage/SDWebImage.h>
// --- end kuikly ---

// --- app (host shell) ---
#import "Kuikly/SDKuiklySupport.h"
#import "Kuikly/KuiklyViewController.h"
#import "Kuikly/SDKuiklyAdapters.h"
// --- end app ---

// --- media ---
// --- end media ---

// --- sdk ---
// 三方 SDK 的 ObjC 适配层（只用到 Foundation / UIKit 类型；微信、极光、友盟的头文件只在各自的 .m 里引入）。
#import "Modules/SDK/SDSDKBridges.h"
// --- end sdk ---
