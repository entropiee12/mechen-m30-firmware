// ===== FUN_00009400 @ 00009400

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_00009400(uint param_1,int *param_2)

{
  char cVar1;
  short sVar2;
  ushort uVar3;
  ushort uVar4;
  uint uVar5;
  int *piVar6;
  int *piVar7;
  int iVar8;
  int iVar9;
  int iVar10;
  undefined4 uStack_28;
  int iStack_24;
  int *piStack_18;
  int *piStack_14;
  
  piVar7 = _UNK_000098f8;
  uStack_28 = 0;
  if (param_2 == (int *)0x0) {
    return 0xffffffff;
  }
  if (param_1 == 0) {
    (*_UNK_000098fc)(_UNK_000098f8,0,0x160,1);
    piVar7[1] = *param_2;
    iVar9 = param_2[1];
    if (iVar9 == 44000) {
      iVar9 = 0xac44;
      param_2[1] = 0xac44;
    }
    *piVar7 = iVar9;
    piVar7[2] = param_2[2];
    iVar9 = param_2[3];
    piVar7[3] = iVar9;
    piVar7[4] = param_2[4];
    if (iVar9 != 0) {
      piVar7[6] = 1;
      return 0;
    }
  }
  else {
    iVar9 = 0;
    if (_UNK_000098f8[10] != 0) {
      (*_UNK_00009900)(2,0,0,0x31);
      iVar9 = piVar7[10];
    }
    if (iVar9 == 0) {
      if (param_1 == 2) {
        iVar9 = *param_2;
        if (iVar9 == 44000) {
          iVar9 = 0xac44;
          *param_2 = 0xac44;
        }
        *piVar7 = iVar9;
      }
      else if (param_1 < 3) {
        if (param_1 == 1) {
          *(ushort *)((int)piVar7 + 0x2e) = (ushort)*(byte *)((int)param_2 + 3);
          piVar7[0xc] = (uint)*(byte *)(param_2 + 1);
          if (*(char *)((int)param_2 + 7) == '\x01') {
            piStack_14 = param_2 + 4;
            iStack_24 = 0;
            piStack_18 = _UNK_000098f8;
            do {
              iVar9 = iStack_24 * 8;
              (*_UNK_000098fc)(_UNK_000098f8 + iStack_24 * 2 + 0x25,piStack_14,8,0);
              if ((*(short *)((int)param_2 + 0xc2) != 0) &&
                 (*(short *)((int)piStack_18 + 0x9a) == 2)) {
                if (*(short *)((int)param_2 + 0xc2) < 1) {
                  *(short *)(piStack_18 + 0x25) = (short)piStack_18[0x25] + (short)param_2[0x30];
                }
                else {
                  *(short *)(piStack_18 + 0x25) = (short)piStack_18[0x25] - (short)param_2[0x30];
                }
              }
              if (((*(short *)((int)param_2 + 0xc6) != 0) &&
                  (*(short *)((int)_UNK_000098f8 + iVar9 + 0x9a) == 1)) &&
                 ((int)(short)_UNK_000098f8[iStack_24 * 2 + 0x25] <=
                  (int)(*(ushort *)(param_2 + 0x31) & 0x7fff))) {
                *(short *)(_UNK_000098f8 + iStack_24 * 2 + 0x26) =
                     (short)_UNK_000098f8[iStack_24 * 2 + 0x26] + *(short *)((int)param_2 + 0xc6);
              }
              if (((*(short *)((int)param_2 + 0xca) != 0) &&
                  (*(short *)((int)_UNK_000098f8 + iVar9 + 0x9a) == 1)) &&
                 ((int)(*(ushort *)(param_2 + 0x32) & 0x7fff) <=
                  (int)(short)_UNK_000098f8[iStack_24 * 2 + 0x25])) {
                *(short *)(_UNK_000098f8 + iStack_24 * 2 + 0x26) =
                     (short)_UNK_000098f8[iStack_24 * 2 + 0x26] + *(short *)((int)param_2 + 0xca);
              }
              piVar7 = _UNK_000098f8;
              iVar8 = 2;
              piVar6 = param_2 + 3;
              do {
                if (((*(short *)((int)piVar6 + 0xc2) != 0) &&
                    (*(short *)((int)piVar7 + iVar9 + 0x9a) == 1)) &&
                   ((int)(short)piVar7[iStack_24 * 2 + 0x25] == (uint)*(ushort *)(piVar6 + 0x30))) {
                  *(short *)(piVar7 + iStack_24 * 2 + 0x26) =
                       (short)piVar7[iStack_24 * 2 + 0x26] + *(short *)((int)piVar6 + 0xc2);
                }
                iVar8 = iVar8 + -1;
                piVar6 = piVar6 + 1;
              } while (-1 < iVar8);
              iStack_24 = iStack_24 + 1;
              piStack_14 = piStack_14 + 2;
              piStack_18 = piStack_18 + 2;
            } while (iStack_24 < 0x10);
            if ((*(short *)((int)_UNK_000098f8 + 0x3a) == 0) && (*_UNK_000098f8 < 0xbb81)) {
              *(undefined2 *)((int)_UNK_000098f8 + 0x3a) = 1;
            }
            _UNK_000098f8[0x24] = 1;
          }
          else {
            *(undefined2 *)((int)piVar7 + 0x3a) = 0;
          }
          piVar6 = _UNK_00009904;
          piVar7 = _UNK_000098f8;
          if ((char)param_2[2] == '\x01') {
            iVar10 = _UNK_00009904[2] * 2 + _UNK_00009908;
            iVar8 = _UNK_00009904[1] * 2 + _UNK_00009908;
            *(undefined2 *)(_UNK_000098f8 + 0xf) = 1;
            *(short *)((int)piVar7 + 0x136) = (short)param_2[0x25];
            *(ushort *)(piVar7 + 0x4e) = (ushort)*(byte *)((int)param_2 + 0x96);
            *(ushort *)((int)piVar7 + 0x13a) = (ushort)*(byte *)((int)param_2 + 0x97);
            iVar9 = _UNK_00009910;
            if (*(char *)((int)param_2 + 0x93) == '\0') {
              if (*piVar6 < (int)(short)param_2[0x25]) {
                (*_UNK_00009900)(&UNK_00009888,0,0,0x4b);
                do {
                    /* WARNING: Do nothing block with infinite loop */
                } while( true );
              }
              iVar9 = (*_UNK_00009900)(_UNK_0000990c,0,0,0xd);
              if (iVar9 == 0) {
                (*_UNK_00009900)(&UNK_000098d4,0,0,0x4b);
                do {
                    /* WARNING: Do nothing block with infinite loop */
                } while( true );
              }
              uVar5 = (*_UNK_00009900)(iVar9,iVar8,(int)(short)param_2[0x25] << 2,0x11);
              if (uVar5 < (uint)((int)(short)param_2[0x25] << 2)) {
                (*_UNK_00009900)(&UNK_000098b0,0,0,0x4b);
                do {
                    /* WARNING: Do nothing block with infinite loop */
                } while( true );
              }
              uVar5 = (*_UNK_00009900)(iVar9,iVar10,(int)(short)param_2[0x25] << 2,0x11);
              if (uVar5 < (uint)((int)(short)param_2[0x25] << 2)) {
                (*_UNK_00009900)(&UNK_000098b0,0,0,0x4b);
                do {
                    /* WARNING: Do nothing block with infinite loop */
                } while( true );
              }
              (*_UNK_00009900)(iVar9,0,0,0xe);
            }
            else {
              (*_UNK_00009900)(iVar8,_UNK_00009910,(int)(short)param_2[0x25] << 2,0x12);
              iVar8 = (short)param_2[0x25] * 4;
              (*_UNK_00009900)(iVar10,iVar8 + iVar9,iVar8,0x12);
            }
            *(undefined2 *)(_UNK_000098f8 + 0x4d) = 1;
          }
          else {
            *(undefined2 *)(_UNK_000098f8 + 0xf) = 0;
          }
          piVar7 = _UNK_000098f8;
          *(ushort *)(_UNK_000098f8 + 0xd) = (ushort)*(byte *)((int)param_2 + 9);
          if (*(char *)((int)param_2 + 9) == '\x01') {
            piVar7[0x11] = (uint)*(ushort *)(param_2 + 0x26);
            cVar1 = *(char *)((int)param_2 + 0x9a);
            piVar7[0x12] = (int)cVar1;
            if ((short)param_2[0x31] < 0) {
              sVar2 = *(short *)((int)param_2 + 0xc6);
              if (sVar2 != 0) {
                iVar9 = (int)cVar1 +
                        (int)(short)((short)((longlong)(int)sVar2 * (longlong)_UNK_00009914 >> 0x22)
                                    - (sVar2 >> 0xf));
                _UNK_000098f8[0x12] = iVar9;
                if (iVar9 < 0xd) {
                  if (iVar9 < 0xffc4) {
                    _UNK_000098f8[0x12] = -0x3c;
                  }
                }
                else {
                  _UNK_000098f8[0x12] = 0xc;
                }
              }
            }
          }
          piVar7 = _UNK_000098f8;
          *(ushort *)((int)_UNK_000098f8 + 0x36) = (ushort)*(byte *)((int)param_2 + 10);
          if (*(char *)((int)param_2 + 10) == '\x01') {
            piVar7[0x17] = (uint)*(byte *)(param_2 + 0x27);
            piVar7[0x18] = (int)*(char *)((int)param_2 + 0x9d);
          }
          piVar7 = _UNK_000098f8;
          *(ushort *)(_UNK_000098f8 + 0xe) = (ushort)*(byte *)((int)param_2 + 0xb);
          if (*(char *)((int)param_2 + 0xb) == '\x01') {
            piVar7[0x19] = (uint)*(ushort *)(param_2 + 0x24);
            cVar1 = *(char *)((int)param_2 + 0x92);
            piVar7[0x1a] = (int)cVar1;
            piVar7 = _UNK_000098f8;
            if ((short)param_2[0x32] < 0) {
              iVar9 = (int)cVar1 +
                      (int)(short)((short)((longlong)(int)*(short *)((int)param_2 + 0xca) *
                                           (longlong)_UNK_00009914 >> 0x22) -
                                  (*(short *)((int)param_2 + 0xca) >> 0xf));
              _UNK_000098f8[0x1a] = iVar9;
              if (iVar9 < 0x10) {
                if (iVar9 < 0) {
                  _UNK_000098f8[0x1a] = 0;
                }
              }
              else {
                piVar7[0x1a] = 0xf;
              }
            }
          }
          piVar7 = _UNK_000098f8;
          *(ushort *)(_UNK_000098f8 + 0x10) = (ushort)*(byte *)(param_2 + 3);
          if ((char)param_2[3] == '\x01') {
            iVar9 = 1;
            piVar6 = param_2;
            do {
              iVar9 = iVar9 + -1;
              piVar7[0x1b] = (uint)*(ushort *)((int)piVar6 + 0x9e);
              piVar6 = (int *)((int)piVar6 + 2);
              piVar7 = piVar7 + 1;
            } while (-1 < iVar9);
            iVar9 = 0;
            do {
              iVar10 = iVar9 * 8;
              iVar8 = iVar9 * 2;
              iVar9 = iVar9 + 1;
              (*_UNK_000098fc)(_UNK_000098f8 + iVar8 + 0x1d,(int)param_2 + iVar10 + 0xa2,8,0);
              piVar7 = _UNK_000098f8;
            } while (iVar9 < 3);
            *(undefined2 *)(_UNK_000098f8 + 0x23) = 0x32;
            *(undefined2 *)((int)piVar7 + 0x8e) = 500;
          }
          piVar7 = _UNK_000098f8;
          *(ushort *)((int)_UNK_000098f8 + 0x3e) = (ushort)*(byte *)((int)param_2 + 0xd);
          if (*(char *)((int)param_2 + 0xd) == '\x01') {
            uVar3 = *(ushort *)(param_2 + 0x2f);
            uVar4 = *(ushort *)((int)param_2 + 0xbe);
            piVar7[0x14] = (int)*(short *)((int)param_2 + 0xba);
            piVar7[0x15] = (uint)uVar3;
            piVar7[0x16] = (uint)uVar4;
          }
        }
      }
      else if (param_1 == 3) {
        piVar7[3] = 0;
        iVar9 = *param_2;
        piVar7[6] = 0;
        piVar7[4] = iVar9;
        if (iVar9 != 0) {
          piVar7[7] = 1;
        }
      }
      else if (param_1 == 4) {
        piVar7[3] = *param_2;
        piVar7[4] = 0;
        piVar7[6] = 1;
        piVar7[7] = 0;
      }
      piVar7 = _UNK_000098f8;
      if ((*_UNK_000098f8 < 0xbb81) || (param_1 != 1)) {
        _UNK_000098f8[10] = 1;
        return 0;
      }
      *(undefined2 *)(_UNK_000098f8 + 0x10) = 0;
      *(undefined2 *)((int)piVar7 + 0x3a) = 0;
      piVar7[10] = 0;
    }
    else {
      uStack_28 = 0xffffffff;
    }
  }
  return uStack_28;
}


