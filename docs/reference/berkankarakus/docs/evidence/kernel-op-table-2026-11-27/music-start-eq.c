// ===== FUN_40094d10 @ 40094d10

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined1 FUN_40094d10(void)

{
  return *_UNK_40094d24;
}


// ===== FUN_40094dd4 @ 40094dd4

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

char FUN_40094dd4(char *param_1,char param_2)

{
  char cVar1;
  undefined1 uVar2;
  int iVar3;
  char cStack_28;
  undefined4 uStack_20;
  undefined1 uStack_1c;
  char cStack_18;
  char *pcStack_14;
  undefined1 uStack_10;
  
  cStack_28 = '\x01';
  cStack_18 = *param_1;
  pcStack_14 = param_1 + 4;
  FUN_40094c00(pcStack_14,param_2,1);
  uStack_20 = 0x204;
  cVar1 = FUN_40094d10();
  if (cVar1 == '\x02') {
    uStack_1c = 0xe;
    (*_UNK_40095048)(8,&uStack_20,0,0);
  }
  *_UNK_4009504c = *_UNK_4009504c & 0xfffff7ff;
  if (cVar1 != '\x01') {
    if (param_2 == '\0') {
      FUN_40094c9c(0);
    }
    else {
      *_UNK_4009504c = *_UNK_4009504c | 0x800;
      if ((*_UNK_40095050 == '\x10') || (*_UNK_40095050 == '\b')) {
        FUN_40094c9c(0x80);
      }
      else {
        FUN_40094c9c(0x40);
      }
    }
    FUN_40094d4c(*(undefined1 *)(_UNK_40095054 + 0x19));
    FUN_40094d28(*(undefined1 *)(_UNK_40095054 + 0x13));
  }
  if (param_2 != '\x01') {
    if ((*_UNK_4009504c & 0x20) == 0) {
      FUN_40614f30(*(byte *)(_UNK_40095054 + 0x26) | *(byte *)(_UNK_40095054 + 0x25));
    }
    else {
      FUN_40614f30(0x10);
    }
  }
  if (param_2 == '\0') {
    *param_1 = *_UNK_40095058;
    cStack_28 = (*_UNK_4009505c)(param_1);
    if (cStack_28 == '\x01') {
      (*_UNK_40095060)();
    }
    else {
      FUN_40614eac();
    }
  }
  else {
    (*_UNK_40095064)(param_1);
    if ((*pcStack_14 == '\x10') && (iVar3 = (*_UNK_40095068)(1,0,0,9), iVar3 == -1)) {
      (*_UNK_4009506c)();
      return '\0';
    }
    if (*(short *)(param_1 + 0x60) == 0) {
      (*_UNK_40095070)(0x8000,3,0x8018);
      FUN_40614eac();
      cStack_28 = '\0';
    }
    else {
      cStack_18 = *_UNK_40095058;
      FUN_40094c00(pcStack_14,param_2,0);
    }
  }
  if (cStack_28 == '\x01') {
    if ((cStack_18 != *param_1) || (_UNK_40095058[4] != *pcStack_14)) {
      (*_UNK_40095074)();
      cStack_28 = FUN_40614db4(param_1);
      if ((*param_1 == '\b') && (param_2 == '\x01')) {
        (*_UNK_40095078)(param_1 + 4,8);
      }
    }
    (*_UNK_4009507c)(_UNK_40095058,param_1,0xa4,0);
    uStack_10 = FUN_400950d4();
    FUN_400950bc(0xff);
    uVar2 = (*_UNK_40095084)(1,0xfa,_UNK_40095080);
    *_UNK_40095088 = uVar2;
    FUN_400950bc(uStack_10);
    if (*pcStack_14 == '\x10') {
      FUN_400950a4(2);
    }
    FUN_4009508c(1);
  }
  return cStack_28;
}


// ===== FUN_40094c00 @ 40094c00

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_40094c00(undefined4 param_1,undefined4 param_2,char param_3)

{
  char cVar1;
  undefined4 uStack_c;
  
  (*_UNK_40094c84)(_UNK_40094c7c,*_UNK_40094c80);
  cVar1 = (*_UNK_40094c8c)(&uStack_c,_UNK_40094c88);
  (*_UNK_40094c90)(uStack_c);
  if (cVar1 == '\x01') {
    if ((*_UNK_40094c94 & 0x2000) == 0) {
      if (param_3 == '\x01') {
        (*_UNK_40094c98)();
      }
      *_UNK_40094c94 = *_UNK_40094c94 | 0x2000;
    }
  }
  else {
    *_UNK_40094c94 = *_UNK_40094c94 & 0xffff9fff;
  }
  return;
}


// ===== FUN_40094c9c @ 40094c9c

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined1 FUN_40094c9c(byte param_1)

{
  undefined1 uVar1;
  undefined4 uStack_20;
  undefined1 uStack_1c;
  byte bStack_1b;
  undefined1 auStack_18 [8];
  char cStack_10;
  
  uVar1 = 1;
  cStack_10 = FUN_40094d10();
  if (cStack_10 == '\0') {
    uStack_1c = 0xd;
    bStack_1b = param_1 | *(byte *)(_UNK_40094d08 + 4);
    uStack_20 = 0x202;
    uVar1 = (*_UNK_40094d0c)(8,&uStack_20,auStack_18,0,uVar1);
  }
  else if (cStack_10 == '\x01') {
    uVar1 = 1;
  }
  else {
    uVar1 = 0;
  }
  return uVar1;
}


// ===== FUN_40094d4c @ 40094d4c

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_40094d4c(undefined1 param_1)

{
  switch(param_1) {
  case 0:
    FUN_403d4e44();
    break;
  case 1:
    FUN_403d4e58();
    break;
  case 2:
    FUN_403d4e6c();
    break;
  case 3:
    FUN_403d4e80();
    break;
  case 4:
    FUN_403d4e94();
    break;
  case 5:
    FUN_403d4ea8();
    break;
  case 6:
    FUN_403d4ebc();
    break;
  case 7:
    FUN_403d4ed0();
    break;
  default:
    FUN_403d4e44();
  }
  return;
}


// ===== FUN_40094dd4 @ 40094dd4

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

char FUN_40094dd4(char *param_1,char param_2)

{
  char cVar1;
  undefined1 uVar2;
  int iVar3;
  char cStack_28;
  undefined4 uStack_20;
  undefined1 uStack_1c;
  char cStack_18;
  char *pcStack_14;
  undefined1 uStack_10;
  
  cStack_28 = '\x01';
  cStack_18 = *param_1;
  pcStack_14 = param_1 + 4;
  FUN_40094c00(pcStack_14,param_2,1);
  uStack_20 = 0x204;
  cVar1 = FUN_40094d10();
  if (cVar1 == '\x02') {
    uStack_1c = 0xe;
    (*_UNK_40095048)(8,&uStack_20,0,0);
  }
  *_UNK_4009504c = *_UNK_4009504c & 0xfffff7ff;
  if (cVar1 != '\x01') {
    if (param_2 == '\0') {
      FUN_40094c9c(0);
    }
    else {
      *_UNK_4009504c = *_UNK_4009504c | 0x800;
      if ((*_UNK_40095050 == '\x10') || (*_UNK_40095050 == '\b')) {
        FUN_40094c9c(0x80);
      }
      else {
        FUN_40094c9c(0x40);
      }
    }
    FUN_40094d4c(*(undefined1 *)(_UNK_40095054 + 0x19));
    FUN_40094d28(*(undefined1 *)(_UNK_40095054 + 0x13));
  }
  if (param_2 != '\x01') {
    if ((*_UNK_4009504c & 0x20) == 0) {
      FUN_40614f30(*(byte *)(_UNK_40095054 + 0x26) | *(byte *)(_UNK_40095054 + 0x25));
    }
    else {
      FUN_40614f30(0x10);
    }
  }
  if (param_2 == '\0') {
    *param_1 = *_UNK_40095058;
    cStack_28 = (*_UNK_4009505c)(param_1);
    if (cStack_28 == '\x01') {
      (*_UNK_40095060)();
    }
    else {
      FUN_40614eac();
    }
  }
  else {
    (*_UNK_40095064)(param_1);
    if ((*pcStack_14 == '\x10') && (iVar3 = (*_UNK_40095068)(1,0,0,9), iVar3 == -1)) {
      (*_UNK_4009506c)();
      return '\0';
    }
    if (*(short *)(param_1 + 0x60) == 0) {
      (*_UNK_40095070)(0x8000,3,0x8018);
      FUN_40614eac();
      cStack_28 = '\0';
    }
    else {
      cStack_18 = *_UNK_40095058;
      FUN_40094c00(pcStack_14,param_2,0);
    }
  }
  if (cStack_28 == '\x01') {
    if ((cStack_18 != *param_1) || (_UNK_40095058[4] != *pcStack_14)) {
      (*_UNK_40095074)();
      cStack_28 = FUN_40614db4(param_1);
      if ((*param_1 == '\b') && (param_2 == '\x01')) {
        (*_UNK_40095078)(param_1 + 4,8);
      }
    }
    (*_UNK_4009507c)(_UNK_40095058,param_1,0xa4,0);
    uStack_10 = FUN_400950d4();
    FUN_400950bc(0xff);
    uVar2 = (*_UNK_40095084)(1,0xfa,_UNK_40095080);
    *_UNK_40095088 = uVar2;
    FUN_400950bc(uStack_10);
    if (*pcStack_14 == '\x10') {
      FUN_400950a4(2);
    }
    FUN_4009508c(1);
  }
  return cStack_28;
}


