// ===== FUN_7f196200 @ 7f196200

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_7f196200(int *param_1)

{
  int iVar1;
  undefined1 auStack_30 [16];
  int iStack_20;
  undefined4 auStack_18 [2];
  undefined1 uStack_10;
  undefined1 auStack_f [3];
  int iStack_c;
  
  iStack_c = *param_1 + -0x201;
  switch(iStack_c) {
  case 0:
    (*_UNK_7f196380)(0xc,7,0,5);
    if ((((char)param_1[1] != *(char *)(_UNK_7f196384 + 0xd)) &&
        (iStack_20 = (*_UNK_7f196388)(auStack_f,0,0,0xf), -1 < iStack_20)) && (iStack_20 != 0xf)) {
      FUN_7f156200(auStack_30,(char)param_1[1]);
      uStack_10 = *(undefined1 *)((int)param_1 + 5);
      (*_UNK_7f19638c)(0,0,0,4);
      (*_UNK_7f19638c)(auStack_30,0,uStack_10,0);
    }
    (*_UNK_7f196380)(0xc,2,0,5);
    break;
  case 1:
    if (*(char *)(_UNK_7f196384 + 0x18) == '\0') {
      FUN_7f156200(auStack_30,(char)param_1[1]);
      uStack_10 = *(undefined1 *)((int)param_1 + 5);
      (*_UNK_7f19638c)(1,0,0,4);
      iVar1 = (*_UNK_7f19638c)(auStack_30,1,uStack_10,0);
      if (iVar1 < 0) {
        *(undefined1 *)param_1[3] = 2;
      }
      else {
        *(undefined1 *)param_1[3] = 1;
      }
      (*_UNK_7f196390)(param_1);
    }
    break;
  default:
    FUN_7f1d6200(param_1);
    break;
  case 3:
    if (*(char *)(_UNK_7f196384 + 0x18) == '\0') {
      *(undefined1 *)param_1[3] = 1;
      (*_UNK_7f196390)(param_1);
    }
    else {
      auStack_18[0] = 0x102;
      (*_UNK_7f196394)((char)param_1[1],auStack_18);
      do {
        do {
          iStack_20 = (*_UNK_7f196388)(auStack_f,0,0,0xf);
        } while (iStack_20 < 0);
      } while (iStack_20 == 0xf);
      *(undefined1 *)param_1[3] = 1;
      (*_UNK_7f196390)(param_1);
    }
    break;
  case 5:
  case 6:
    break;
  }
  return;
}


