from pathlib import Path
import json
import onnx
from onnx import helper as h, TensorProto as T
root=Path('plugin-onnx/src/jvmTest/resources/tiny-onnx');root.mkdir(parents=True,exist_ok=True)
# Deterministic byte-token successor model. Synthetic weights, no third-party model data.
inputs=[h.make_tensor_value_info('input_ids',T.INT64,[1,'sequence']),h.make_tensor_value_info('attention_mask',T.INT64,[1,'total']),h.make_tensor_value_info('position_ids',T.INT64,[1,'sequence']),h.make_tensor_value_info('past_key_values.0.key',T.FLOAT,[1,1,'past',2]),h.make_tensor_value_info('past_key_values.0.value',T.FLOAT,[1,1,'past',2])]
inits=[h.make_tensor('one',T.INT64,[],[1]),h.make_tensor('depth',T.INT64,[],[256]),h.make_tensor('values',T.FLOAT,[2],[-10,10])]
nodes=[h.make_node('Add',['input_ids','one'],['next']),h.make_node('Mod',['next','depth'],['indices']),h.make_node('OneHot',['indices','depth','values'],['logits'],axis=-1),h.make_node('Identity',['past_key_values.0.key'],['present.0.key']),h.make_node('Identity',['past_key_values.0.value'],['present.0.value'])]
outputs=[h.make_tensor_value_info('logits',T.FLOAT,[1,'sequence',256]),h.make_tensor_value_info('present.0.key',T.FLOAT,[1,1,'past',2]),h.make_tensor_value_info('present.0.value',T.FLOAT,[1,1,'past',2])]
model=h.make_model(h.make_graph(nodes,'byte-successor',inputs,outputs,inits),opset_imports=[h.make_opsetid('',17)],ir_version=9)
onnx.checker.check_model(model);onnx.save(model,root/'model.onnx')
direct=set(range(33,127))|set(range(161,173))|set(range(174,256)); n=256;vocab={}
for i in range(256):
    if i in direct: c=chr(i)
    else: c=chr(n);n+=1
    vocab[c]=i
(root/'tokenizer.json').write_text(json.dumps({'model':{'type':'BPE','vocab':vocab,'merges':[]},'pre_tokenizer':{'type':'ByteLevel','add_prefix_space':False,'use_regex':False},'decoder':{'type':'ByteLevel'},'added_tokens':[]}))
(root/'config.json').write_text(json.dumps({'model_type':'gpt2','eos_token_id':0,'max_position_embeddings':512,'hidden_size':2,'num_attention_heads':1,'num_hidden_layers':1}))
